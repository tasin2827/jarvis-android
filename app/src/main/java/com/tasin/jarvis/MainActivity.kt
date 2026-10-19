package com.tasin.jarvis

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasin.jarvis.accessibility.JarvisAccessibilityService

private val Navy = Color(0xFF050B1A)
private val Cyan = Color(0xFF00E5FF)
private val Blue = Color(0xFF2979FF)
private val Green = Color(0xFF00E676)
private val Red = Color(0xFFFF5252)

class MainActivity : ComponentActivity() {

    private var serviceConnected by mutableStateOf(false)
    private var lastResult by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisHome(
                connected = serviceConnected,
                result = lastResult,
                onOpenSettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onTestHome = { runGlobal("Home") { it.pressHome() } },
                onTestBack = { runGlobal("Back") { it.pressBack() } }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        serviceConnected = JarvisAccessibilityService.instance != null
    }

    private fun runGlobal(name: String, action: (JarvisAccessibilityService) -> Boolean) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            serviceConnected = false
            lastResult = "Service is not connected. Enable it in Accessibility settings."
            return
        }
        val accepted = action(service)
        lastResult = if (accepted) {
            "$name: Android accepted the command (screen change not verified yet)."
        } else {
            "$name: Android rejected the command."
        }
    }
}

@Composable
private fun JarvisHome(
    connected: Boolean,
    result: String,
    onOpenSettings: () -> Unit,
    onTestHome: () -> Unit,
    onTestBack: () -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Navy)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Navy)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ArcReactor()
            Spacer(Modifier.height(24.dp))
            Text(
                text = "JARVIS",
                color = Cyan,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(text = "Build v0.3 - accessibility stage", color = Color(0xFF8FA3BF), fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))

            Text(
                text = if (connected) "Accessibility service: ON" else "Accessibility service: OFF",
                color = if (connected) Green else Red,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (connected) {
                    "Connected. The two buttons below send real Home and Back commands."
                } else {
                    "Not connected yet. Tap the button below, find JARVIS AI Assistant in the list and switch it on."
                },
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
            ) {
                Text("Open Accessibility settings")
            }

            if (connected) {
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = onTestHome,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)
                    ) {
                        Text("Test: Home")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onTestBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)
                    ) {
                        Text("Test: Back")
                    }
                }
            }

            if (result.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(text = result, color = Color(0xFF8FA3BF), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ArcReactor() {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    Canvas(modifier = Modifier.size(160.dp)) {
        val outerInset = 6.dp.toPx()
        val innerInset = 26.dp.toPx()
        drawArc(
            color = Cyan,
            startAngle = angle,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(outerInset, outerInset),
            size = Size(size.width - 2 * outerInset, size.height - 2 * outerInset),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = Blue,
            startAngle = -angle,
            sweepAngle = 200f,
            useCenter = false,
            topLeft = Offset(innerInset, innerInset),
            size = Size(size.width - 2 * innerInset, size.height - 2 * innerInset),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )
        drawCircle(color = Cyan, radius = 13.dp.toPx())
    }
}
