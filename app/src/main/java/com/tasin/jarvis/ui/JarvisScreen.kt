package com.tasin.jarvis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasin.jarvis.automation.JarvisController
import com.tasin.jarvis.domain.JarvisState
import com.tasin.jarvis.domain.Role

private val Navy = Color(0xFF050B1A)
private val Panel = Color(0xFF0D1830)
private val Cyan = Color(0xFF00E5FF)
private val Blue = Color(0xFF2979FF)
private val Green = Color(0xFF00E676)
private val Red = Color(0xFFFF5252)
private val Amber = Color(0xFFFFB300)
private val Muted = Color(0xFF8FA3BF)

@Composable
fun JarvisApp(
    accessibilityOn: Boolean,
    onMic: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val c = JarvisController
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(c.messages.size) {
        if (c.messages.isNotEmpty()) listState.animateScrollToItem(c.messages.size - 1)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Navy)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Navy, contentColor = Color.White) {
            if (showSettings) {
                SettingsPanel(onClose = { showSettings = false })
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Header(accessibilityOn = accessibilityOn, onSettings = { showSettings = true })

                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Reactor(c.state, 100.dp)
                    }
                    Text(
                        text = c.statusLine,
                        color = Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (c.heard.isNotEmpty()) {
                        Text(
                            text = "\"${c.heard}\"",
                            color = Green,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    if (!accessibilityOn) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Panel, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                "Accessibility is OFF. Without it I can talk but cannot touch the screen.",
                                color = Amber,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = onOpenAccessibility,
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
                            ) { Text("Open Accessibility settings") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(c.messages) { m -> Bubble(m.role, m.text) }
                    }

                    c.pendingConfirmation?.let { question ->
                        Spacer(Modifier.height(8.dp))
                        ConfirmCard(question)
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type a command...", color = Muted) },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                c.submit(input)
                                input = ""
                            },
                            enabled = input.isNotBlank()
                        ) { Text("Send") }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onMic,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (c.state == JarvisState.LISTENING) Green else Cyan,
                                contentColor = Navy
                            )
                        ) {
                            Text(if (c.state == JarvisState.LISTENING) "Listening... tap to stop" else "Talk")
                        }
                        OutlinedButton(onClick = { c.cancel() }) { Text("Cancel", color = Red) }
                        OutlinedButton(onClick = { c.toggleLanguage() }) {
                            Text(if (c.language == "bn-BD") "বাংলা" else "EN", color = Cyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(accessibilityOn: Boolean, onSettings: () -> Unit) {
    val c = JarvisController
    val aiLine = when {
        !c.cloudEnabled -> "AI: local commands only"
        c.hasKey -> "AI: Gemini key saved (${c.model})"
        else -> "AI: no Gemini key yet"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("JARVIS", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Text(
                "Accessibility: ${if (accessibilityOn) "ON" else "OFF"}",
                color = if (accessibilityOn) Green else Red,
                fontSize = 12.sp
            )
            Text(aiLine, color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = onSettings) { Text("Settings", color = Cyan) }
    }
}

@Composable
private fun Bubble(role: Role, text: String) {
    val bg: Color
    val fg: Color
    val align: Alignment.Horizontal
    when (role) {
        Role.USER -> { bg = Blue; fg = Color.White; align = Alignment.End }
        Role.JARVIS -> { bg = Panel; fg = Cyan; align = Alignment.Start }
        Role.SYSTEM -> { bg = Color.Transparent; fg = Muted; align = Alignment.Start }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(
            Modifier
                .background(bg, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text, color = fg, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ConfirmCard(question: String) {
    val c = JarvisController
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(question, color = Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { c.answerConfirmation(true) },
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
            ) { Text("Yes, do it") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { c.answerConfirmation(false) }) { Text("No", color = Red) }
        }
    }
}

@Composable
private fun Reactor(state: JarvisState, diameter: Dp) {
    val color = when (state) {
        JarvisState.LISTENING -> Green
        JarvisState.THINKING -> Amber
        JarvisState.EXECUTING -> Blue
        JarvisState.ERROR -> Red
        else -> Cyan
    }
    val duration = when (state) {
        JarvisState.LISTENING -> 2500
        JarvisState.THINKING, JarvisState.EXECUTING -> 1500
        else -> 7000
    }
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    Canvas(modifier = Modifier.size(diameter)) {
        val outer = 4.dp.toPx()
        val inner = diameter.toPx() * 0.17f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(outer, outer),
            size = Size(size.width - 2 * outer, size.height - 2 * outer),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = Blue,
            startAngle = -angle,
            sweepAngle = 200f,
            useCenter = false,
            topLeft = Offset(inner, inner),
            size = Size(size.width - 2 * inner, size.height - 2 * inner),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        drawCircle(color = color, radius = diameter.toPx() * 0.08f)
    }
}

@Composable
private fun SettingsPanel(onClose: () -> Unit) {
    val c = JarvisController
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(c.model) }
    var cloud by remember { mutableStateOf(c.cloudEnabled) }
    var voice by remember { mutableStateOf(c.voiceReplies) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings and privacy", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "What is sent to Google's Gemini API: only the text of your command and the package name of the app on screen. " +
                "Screen text is never sent. Simple commands (open, back, home, scroll) are handled on the phone. " +
                "Your key is stored in this app's private storage without encryption, so someone with deep access to this phone could extract it. " +
                "Never paste the key into a chat.",
            color = Muted,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (c.hasKey) "API key saved - type to replace" else "Gemini API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = cloud, onCheckedChange = { cloud = it })
            Spacer(Modifier.width(12.dp))
            Text("Use cloud AI (Gemini)", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = voice, onCheckedChange = { voice = it })
            Spacer(Modifier.width(12.dp))
            Text("Speak replies aloud", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                c.saveSettings(key, model, cloud, voice)
                key = ""
                onClose()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { c.clearHistory() }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear history", color = Cyan)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { c.removeKey() }, modifier = Modifier.fillMaxWidth()) {
            Text("Remove saved API key", color = Red)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Back", color = Muted)
        }
    }
}
