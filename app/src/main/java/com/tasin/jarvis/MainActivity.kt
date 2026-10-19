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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF050B1A)
private val Cyan = Color(0xFF00E5FF)
private val Blue = Color(0xFF2979FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisHome() }
    }
}

@Composable
private fun JarvisHome() {
    val context = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Navy)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Navy).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ArcReactor()
            Spacer(Modifier.height(32.dp))
            Text(
                text = "JARVIS",
                color = Cyan,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(text = "Build v0.2 - setup stage", color = Color(0xFF8FA3BF), fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Voice and screen control are not built yet. The button below opens Android's real Accessibility settings.",
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
            ) {
                Text("Open Accessibility settings")
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
    Canvas(modifier = Modifier.size(180.dp)) {
        val outerInset = 6.dp.toPx()
        val innerInset = 28.dp.toPx()
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
        drawCircle(color = Cyan, radius = 14.dp.toPx())
    }
}
