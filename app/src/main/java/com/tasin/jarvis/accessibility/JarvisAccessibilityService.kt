package com.tasin.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen reading and verification are added in later batches.
    }

    override fun onInterrupt() {
        // Nothing to interrupt yet.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }
}
