package com.tasin.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tasin.jarvis.accessibility.JarvisAccessibilityService
import com.tasin.jarvis.automation.JarvisController
import com.tasin.jarvis.domain.JarvisState
import com.tasin.jarvis.ui.JarvisApp

class MainActivity : ComponentActivity() {

    private var serviceConnected by mutableStateOf(false)

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) JarvisController.startListening() else JarvisController.onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisController.init(applicationContext)
        setContent {
            JarvisApp(
                accessibilityOn = serviceConnected,
                onMic = { onMicClick() },
                onOpenAccessibility = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        serviceConnected = JarvisAccessibilityService.instance != null
    }

    private fun onMicClick() {
        if (JarvisController.state == JarvisState.LISTENING) {
            JarvisController.stopListening()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) JarvisController.startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
