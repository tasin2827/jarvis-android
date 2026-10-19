package com.tasin.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class ClickOutcome { CLICKED, TAPPED_BY_COORDINATES, NOT_FOUND, NO_SCREEN, REJECTED }
enum class TypeOutcome { VERIFIED, UNVERIFIED, NO_FIELD, PASSWORD_FIELD, NO_SCREEN, REJECTED }

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing is recorded. Screen content is only read when a command asks for it.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Global actions ----

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ---- Screen inspection ----

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** A cheap fingerprint of what is on screen, used to check whether an action changed anything. */
    fun screenSignature(): String {
        val root = rootInActiveWindow ?: return "no-window"
        val sb = StringBuilder()
        sb.append(root.packageName).append('|')
        walk(root, intArrayOf(300)) { n ->
            val t = n.text?.toString()
            if (!t.isNullOrEmpty() && !n.isPassword) sb.append(t).append('\u0001')
        }
        return sb.toString().hashCode().toString()
    }

    /** Visible text only. Password fields are always skipped. */
    fun readScreenText(maxChars: Int = 1500): String {
        val root = rootInActiveWindow ?: return ""
        val parts = ArrayList<String>()
        walk(root, intArrayOf(400)) { n ->
            if (n.isVisibleToUser && !n.isPassword) {
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                val v = if (t.isNotEmpty()) t else d
                if (v.isNotEmpty()) parts.add(v)
            }
        }
        return parts.joinToString(". ").take(maxChars)
    }

    // ---- Actions ----

    suspend fun findAndClick(label: String): ClickOutcome {
        val root = rootInActiveWindow ?: return ClickOutcome.NO_SCREEN
        val matches = root.findAccessibilityNodeInfosByText(label)
        if (matches.isNullOrEmpty()) return ClickOutcome.NOT_FOUND
        val target = matches.firstOrNull { it.isVisibleToUser && !it.isPassword }
            ?: return ClickOutcome.NOT_FOUND

        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth < 8) {
            if (node.isClickable && node.isEnabled) {
                return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    ClickOutcome.CLICKED
                } else {
                    ClickOutcome.REJECTED
                }
            }
            node = node.parent
            depth++
        }

        // Fallback: tap the centre of the element.
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return ClickOutcome.NOT_FOUND
        val ok = tap(bounds.exactCenterX(), bounds.exactCenterY())
        return if (ok) ClickOutcome.TAPPED_BY_COORDINATES else ClickOutcome.REJECTED
    }

    suspend fun typeText(text: String): TypeOutcome {
        val root = rootInActiveWindow ?: return TypeOutcome.NO_SCREEN
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target: AccessibilityNodeInfo = if (focused != null && focused.isEditable) {
            focused
        } else {
            findFirst(root) { it.isEditable && it.isVisibleToUser } ?: return TypeOutcome.NO_FIELD
        }
        if (target.isPassword) return TypeOutcome.PASSWORD_FIELD

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return TypeOutcome.REJECTED
        delay(250)
        target.refresh()
        return if (target.text?.toString() == text) TypeOutcome.VERIFIED else TypeOutcome.UNVERIFIED
    }

    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    suspend fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findFirst(root) { it.isScrollable && it.isVisibleToUser }
        if (node != null) {
            val action = if (direction == "down" || direction == "right") {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.performAction(action)) return true
        }
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        return when (direction) {
            "down" -> swipe(w / 2, h * 0.7f, w / 2, h * 0.3f)
            "up" -> swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        }
    }

    suspend fun tap(x: Float, y: Float): Boolean =
        dispatch(Path().apply { moveTo(x, y) }, 60L)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350L): Boolean =
        dispatch(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }

    // ---- Helpers ----

    private fun walk(node: AccessibilityNodeInfo, budget: IntArray, visit: (AccessibilityNodeInfo) -> Unit) {
        if (budget[0] <= 0) return
        budget[0]--
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, budget, visit)
        }
    }

    private fun findFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root, intArrayOf(500)) { n ->
            if (found == null && predicate(n)) found = n
        }
        return found
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }
}
