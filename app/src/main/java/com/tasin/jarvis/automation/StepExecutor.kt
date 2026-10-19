package com.tasin.jarvis.automation

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.tasin.jarvis.accessibility.ClickOutcome
import com.tasin.jarvis.accessibility.JarvisAccessibilityService
import com.tasin.jarvis.accessibility.TypeOutcome
import com.tasin.jarvis.domain.ActionType
import com.tasin.jarvis.domain.Step
import com.tasin.jarvis.domain.StepResult
import kotlinx.coroutines.delay

data class AppInfo(val label: String, val packageName: String)

object AppResolver {
    fun resolve(context: Context, name: String): AppInfo? {
        val q = name.trim().lowercase()
        if (q.length < 2) return null
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }

        return apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.filter { it.label.lowercase().contains(q) }.minByOrNull { it.label.length }
            ?: apps.firstOrNull { it.label.length >= 3 && q.contains(it.label.lowercase()) }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(q.replace(" ", "")) }
    }
}

/** Runs one step and checks whether it really had an effect. */
class StepExecutor(private val context: Context) {

    suspend fun execute(step: Step): StepResult {
        val svc = JarvisAccessibilityService.instance
            ?: return StepResult(false, false, "The accessibility service is not connected.")
        return when (step.action) {
            ActionType.OPEN_APP -> openApp(svc, step)
            ActionType.CLICK_TEXT -> click(svc, step)
            ActionType.TYPE_TEXT -> type(svc, step)
            ActionType.PRESS_ENTER -> pressEnter(svc, step)
            ActionType.SCROLL -> scroll(svc, step)
            ActionType.READ_SCREEN -> readScreen(svc)
            ActionType.BACK -> global(svc, "Back", step) { svc.pressBack() }
            ActionType.HOME -> global(svc, "Home", step) { svc.pressHome() }
            ActionType.RECENTS -> global(svc, "Recents", step) { svc.pressRecents() }
            ActionType.OPEN_SETTINGS -> openSettings(svc, step)
        }
    }

    private suspend fun openApp(svc: JarvisAccessibilityService, step: Step): StepResult {
        val name = step.app ?: return StepResult(false, false, "No app name was given.")
        val app = AppResolver.resolve(context, name)
            ?: return StepResult(false, false, "I couldn't find an installed app called \"$name\".")
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return StepResult(false, false, "${app.label} cannot be launched.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            svc.startActivity(intent)
        } catch (e: Exception) {
            return StepResult(false, false, "Android did not let me start ${app.label}.")
        }
        val opened = waitFor(step.timeoutMs.coerceAtLeast(6000L)) { svc.foregroundPackage() == app.packageName }
        return if (opened) {
            StepResult(true, true, "Opened ${app.label} (checked: it is on screen).")
        } else {
            StepResult(false, false, "I started ${app.label} but couldn't verify that it is on screen.")
        }
    }

    private suspend fun click(svc: JarvisAccessibilityService, step: Step): StepResult {
        val label = step.text ?: return StepResult(false, false, "No button label was given.")
        val before = svc.screenSignature()
        var outcome = ClickOutcome.NOT_FOUND
        for (attempt in 1..4) {
            outcome = svc.findAndClick(label)
            if (outcome != ClickOutcome.NOT_FOUND && outcome != ClickOutcome.NO_SCREEN) break
            delay(700)
        }
        return when (outcome) {
            ClickOutcome.NOT_FOUND, ClickOutcome.NO_SCREEN ->
                StepResult(false, false, "I couldn't find \"$label\" on this screen.")
            ClickOutcome.REJECTED ->
                StepResult(false, false, "Android did not accept the tap on \"$label\".")
            ClickOutcome.CLICKED, ClickOutcome.TAPPED_BY_COORDINATES -> {
                val changed = waitForChange(svc, before, step.timeoutMs)
                if (changed) StepResult(true, true, "Tapped \"$label\" (checked: the screen changed).")
                else StepResult(true, false, "Tapped \"$label\" but I couldn't verify that anything changed.")
            }
        }
    }

    private suspend fun type(svc: JarvisAccessibilityService, step: Step): StepResult {
        val text = step.text ?: return StepResult(false, false, "No text was given.")
        var outcome = TypeOutcome.NO_FIELD
        for (attempt in 1..4) {
            outcome = svc.typeText(text)
            if (outcome != TypeOutcome.NO_FIELD && outcome != TypeOutcome.NO_SCREEN) break
            delay(600)
        }
        return when (outcome) {
            TypeOutcome.VERIFIED -> StepResult(true, true, "Typed the text (checked: the field shows it).")
            TypeOutcome.UNVERIFIED -> StepResult(true, false, "I entered the text but couldn't verify that the field shows it.")
            TypeOutcome.NO_FIELD, TypeOutcome.NO_SCREEN -> StepResult(false, false, "I couldn't find a text field to type into.")
            TypeOutcome.PASSWORD_FIELD -> StepResult(false, false, "That is a password field. I never type into password fields.")
            TypeOutcome.REJECTED -> StepResult(false, false, "The field did not accept the text.")
        }
    }

    private suspend fun pressEnter(svc: JarvisAccessibilityService, step: Step): StepResult {
        val before = svc.screenSignature()
        if (!svc.pressEnter()) {
            return StepResult(false, false, "I couldn't press Enter (needs Android 11+ and a focused text field).")
        }
        val changed = waitForChange(svc, before, 3000L)
        return if (changed) StepResult(true, true, "Pressed Enter (checked: the screen changed).")
        else StepResult(true, false, "Pressed Enter but I couldn't verify that anything changed.")
    }

    private suspend fun scroll(svc: JarvisAccessibilityService, step: Step): StepResult {
        val dir = step.direction ?: "down"
        val before = svc.screenSignature()
        if (!svc.scroll(dir)) return StepResult(false, false, "Android did not accept the scroll $dir.")
        val changed = waitForChange(svc, before, 1500L)
        return if (changed) StepResult(true, true, "Scrolled $dir (checked: the content changed).")
        else StepResult(true, false, "Scrolled $dir but nothing changed. This may be the end of the list.")
    }

    private fun readScreen(svc: JarvisAccessibilityService): StepResult {
        val text = svc.readScreenText()
        return if (text.isBlank()) {
            StepResult(false, false, "I can't see readable text here. This app may not expose it to accessibility.")
        } else {
            StepResult(true, true, text)
        }
    }

    private suspend fun global(
        svc: JarvisAccessibilityService,
        name: String,
        step: Step,
        action: () -> Boolean
    ): StepResult {
        val before = svc.screenSignature()
        if (!action()) return StepResult(false, false, "Android rejected the $name command.")
        val changed = waitForChange(svc, before, step.timeoutMs)
        return if (changed) StepResult(true, true, "$name done (checked: the screen changed).")
        else StepResult(true, false, "$name sent but I couldn't verify a change.")
    }

    private suspend fun openSettings(svc: JarvisAccessibilityService, step: Step): StepResult {
        val action = when (step.text) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val before = svc.foregroundPackage()
        try {
            svc.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return StepResult(false, false, "Android did not let me open that settings page.")
        }
        val changed = waitFor(5000L) { svc.foregroundPackage() != before }
        return if (changed) StepResult(true, true, "Opened the settings page. You flip the switch yourself.")
        else StepResult(true, false, "I asked Android to open the settings page but couldn't verify it.")
    }

    private suspend fun waitForChange(svc: JarvisAccessibilityService, before: String, timeoutMs: Long): Boolean =
        waitFor(timeoutMs) { svc.screenSignature() != before }

    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (condition()) return true
            if (SystemClock.uptimeMillis() >= end) return false
            delay(250)
        }
    }
}
