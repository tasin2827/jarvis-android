package com.tasin.jarvis.automation

import android.content.Context
import android.os.PowerManager

object ScreenController {
    fun wakeUpScreen(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "Jarvis::ScreenWake"
        )
        wl.acquire(3000L)
    }
}
