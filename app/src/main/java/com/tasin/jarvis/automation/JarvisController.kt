package com.tasin.jarvis.automation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tasin.jarvis.accessibility.JarvisAccessibilityService
import com.tasin.jarvis.ai.AiProvider
import com.tasin.jarvis.ai.GeminiProvider
import com.tasin.jarvis.ai.LocalCommandParser
import com.tasin.jarvis.data.JarvisSettings
import com.tasin.jarvis.domain.ActionType
import com.tasin.jarvis.domain.ChatMessage
import com.tasin.jarvis.domain.JarvisState
import com.tasin.jarvis.domain.Plan
import com.tasin.jarvis.domain.Risk
import com.tasin.jarvis.domain.RiskLevel
import com.tasin.jarvis.domain.Role
import com.tasin.jarvis.domain.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

object JarvisController {

    val messages = mutableStateListOf<ChatMessage>()

    var state by mutableStateOf(JarvisState.IDLE)
        private set
    var statusLine by mutableStateOf("Starting...")
        private set
    var heard by mutableStateOf("")
        private set
    var pendingConfirmation by mutableStateOf<String?>(null)
        private set
    var language by mutableStateOf("bn-BD")
        private set
    var cloudEnabled by mutableStateOf(true)
        private set
    var voiceReplies by mutableStateOf(true)
        private set
    var model by mutableStateOf(JarvisSettings.DEFAULT_MODEL)
        private set
    var hasKey by mutableStateOf(false)
        private set

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var settings: JarvisSettings
    private lateinit var executor: StepExecutor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val needsOtherApp = setOf(
        ActionType.CLICK_TEXT, ActionType.TYPE_TEXT, ActionType.PRESS_ENTER,
        ActionType.SCROLL, ActionType.READ_SCREEN, ActionType.BACK
    )

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settings = JarvisSettings(appContext)
        executor = StepExecutor(appContext)
        language = settings.language
        cloudEnabled = settings.cloudEnabled
        voiceReplies = settings.voiceReplies
        model = settings.model
        hasKey = settings.apiKey.isNotBlank()
        tts = TextToSpeech(appContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        state = JarvisState.IDLE
        statusLine = "Ready"
        addMessage(Role.JARVIS, "JARVIS is ready. Tap Talk or type a command. Simple commands work without the cloud.")
    }

    // ---------------- Input ----------------

    fun submit(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || !initialized) return
        addMessage(Role.USER, text)
        if (LocalCommandParser.isStop(text)) {
            cancelCurrent("Stopped.")
            return
        }
        if (job?.isActive == true) {
            addMessage(Role.JARVIS, "I'm still working on the previous task. Say stop or press Cancel first.")
            return
        }
        job = scope.launch { runRequest(text) }
    }

    fun cancel() {
        if (initialized) cancelCurrent("Cancelled.")
    }

    fun answerConfirmation(yes: Boolean) {
        confirmation?.complete(yes)
    }

    // ---------------- Voice ----------------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) heard = p
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            heard = ""
            restoreState()
            if (text.isNullOrBlank()) {
                addMessage(Role.SYSTEM, "I didn't catch anything. Tap Talk and try again.")
            } else {
                submit(text)
            }
        }

        override fun onError(error: Int) {
            heard = ""
            restoreState()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "I didn't catch anything. Tap Talk and try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Microphone permission is missing."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Speech recognition needs an internet connection right now."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "The speech recognizer is busy. Try again in a moment."
                SpeechRecognizer.ERROR_AUDIO ->
                    "There was a problem with the microphone."
                else -> "Speech recognition failed (code $error). You can type instead."
            }
            addMessage(Role.SYSTEM, msg)
        }
    }

    fun startListening() {
        if (!initialized) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            addMessage(
                Role.SYSTEM,
                "Speech recognition is not available on this phone (the Google speech service may be missing). Type your command instead."
            )
            return
        }
        tts?.stop()
        recognizer?.destroy()
        heard = ""
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        state = JarvisState.LISTENING
        statusLine = "Listening..."
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun onMicDenied() {
        addMessage(
            Role.SYSTEM,
            "Microphone permission was denied. You can still type commands, or allow the microphone in Settings > Apps > JARVIS > Permissions."
        )
    }

    // ---------------- Settings ----------------

    fun saveSettings(newKey: String, newModel: String, cloud: Boolean, voice: Boolean) {
        if (newKey.isNotBlank()) {
            settings.apiKey = newKey
            hasKey = true
        }
        settings.model = newModel
        model = settings.model
        settings.cloudEnabled = cloud
        cloudEnabled = cloud
        settings.voiceReplies = voice
        voiceReplies = voice
        addMessage(Role.SYSTEM, "Settings saved.")
    }

    fun removeKey() {
        settings.apiKey = ""
        hasKey = false
        addMessage(Role.SYSTEM, "The API key was removed from this phone.")
    }

    fun clearHistory() {
        messages.clear()
    }

    fun toggleLanguage() {
        language = if (language == "bn-BD") "en-US" else "bn-BD"
        settings.language = language
    }

    // ---------------- Request pipeline ----------------

    private suspend fun runRequest(text: String) {
        try {
            state = JarvisState.THINKING
            statusLine = "Thinking..."
            val plan = obtainPlan(text) ?: return

            if (plan.reply.isNotBlank()) say(plan.reply)
            if (plan.steps.isEmpty()) {
                finish(JarvisState.DONE, "Ready")
                return
            }
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                say("The accessibility service is off, so I can't control the screen. Turn it on first.")
                finish(JarvisState.ERROR, "Accessibility is off")
                return
            }
            waitIfJarvisInFront(plan.steps.first(), svc)
            state = JarvisState.EXECUTING
            runSteps(plan.steps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("Something went wrong (${e.javaClass.simpleName}).")
            finish(JarvisState.ERROR, "Error")
        }
    }

    private suspend fun obtainPlan(text: String): Plan? {
        val local = LocalCommandParser.parse(text)
        if (local != null) {
            addMessage(Role.SYSTEM, "Handled on the phone. Nothing was sent to the cloud.")
            return local
        }
        if (!cloudEnabled) {
            say("Cloud AI is turned off, so I can only do simple commands like open, back, home and scroll.")
            finish(JarvisState.IDLE, "Ready")
            return null
        }
        val key = settings.apiKey
        if (key.isBlank()) {
            say("I need a Gemini API key for that. Add it in Settings.")
            finish(JarvisState.IDLE, "Ready")
            return null
        }
        addMessage(Role.SYSTEM, "Sending your command text to Gemini...")
        val provider: AiProvider = GeminiProvider(key, settings.model)
        val result = provider.plan(text, JarvisAccessibilityService.instance?.foregroundPackage())
        val plan = result.getOrNull()
        if (plan == null) {
            val reason = result.exceptionOrNull()?.message ?: "Unknown error."
            say("I couldn't understand that. $reason")
            finish(JarvisState.ERROR, "AI error")
            return null
        }
        return plan
    }

    /** Talk is pressed inside JARVIS, so screen commands would hit JARVIS itself. Give time to switch apps. */
    private suspend fun waitIfJarvisInFront(first: Step, svc: JarvisAccessibilityService) {
        if (first.action !in needsOtherApp) return
        if (svc.foregroundPackage() != appContext.packageName) return
        say("Switch to the app you want me to control. I'll start in 6 seconds.")
        for (s in 6 downTo 1) {
            statusLine = "Starting in $s s - open the target app"
            delay(1000)
        }
    }

    private suspend fun runSteps(steps: List<Step>) {
        var unverified = 0
        for ((index, step) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                say("The accessibility service disconnected, so I stopped.")
                finish(JarvisState.ERROR, "Accessibility is off")
                return
            }
            statusLine = "Step ${index + 1}/${steps.size}: ${describe(step)}"

            if (needsConfirmation(step, svc)) {
                val approved = askConfirmation("Do you want me to ${describe(step)}?")
                if (!approved) {
                    say("Okay, I did not do that. I stopped here.")
                    finish(JarvisState.IDLE, "Ready")
                    return
                }
            }

            val result = executor.execute(step)
            if (step.action == ActionType.READ_SCREEN && result.ok) {
                addMessage(Role.SYSTEM, "Screen text:\n${result.message}")
                speak(result.message.take(300))
            } else {
                addMessage(Role.SYSTEM, (if (result.ok) "OK: " else "FAILED: ") + result.message)
            }
            if (!result.ok) {
                say("I stopped here.")
                finish(JarvisState.ERROR, "Stopped")
                return
            }
            if (!result.verified) unverified++
            delay(600)
        }
        if (unverified == 0) {
            say("Done. I checked every step.")
        } else {
            say("Finished, but I couldn't verify $unverified step(s). Please check the screen.")
        }
        finish(JarvisState.DONE, "Ready")
    }

    private fun needsConfirmation(step: Step, svc: JarvisAccessibilityService): Boolean {
        if (step.risk == RiskLevel.HIGH) return true
        if (step.action == ActionType.PRESS_ENTER && Risk.isMessagingPackage(svc.foregroundPackage())) return true
        return false
    }

    private suspend fun askConfirmation(question: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        confirmation = d
        pendingConfirmation = question
        say(question)
        return try {
            withTimeoutOrNull(60_000L) { d.await() } ?: false
        } finally {
            pendingConfirmation = null
            confirmation = null
        }
    }

    private fun describe(step: Step): String = when (step.action) {
        ActionType.OPEN_APP -> "open ${step.app}"
        ActionType.CLICK_TEXT -> "tap \"${step.text}\""
        ActionType.TYPE_TEXT -> "type text into the focused field"
        ActionType.PRESS_ENTER -> "press Enter"
        ActionType.SCROLL -> "scroll ${step.direction}"
        ActionType.READ_SCREEN -> "read the screen"
        ActionType.BACK -> "go back"
        ActionType.HOME -> "go to Home"
        ActionType.RECENTS -> "open recent apps"
        ActionType.OPEN_SETTINGS -> "open ${step.text ?: "main"} settings"
    }

    // ---------------- Helpers ----------------

    private fun cancelCurrent(message: String) {
        recognizer?.cancel()
        tts?.stop()
        job?.cancel()
        confirmation?.complete(false)
        pendingConfirmation = null
        heard = ""
        finish(JarvisState.IDLE, "Ready")
        addMessage(Role.JARVIS, message)
    }

    private fun restoreState() {
        if (job?.isActive == true) {
            state = JarvisState.EXECUTING
        } else {
            state = JarvisState.IDLE
            statusLine = "Ready"
        }
    }

    private fun finish(newState: JarvisState, status: String) {
        state = newState
        statusLine = status
    }

    private fun say(text: String) {
        addMessage(Role.JARVIS, text)
        speak(text)
    }

    private fun speak(text: String) {
        if (!ttsReady || !voiceReplies) return
        val engine = tts ?: return
        val bangla = text.any { it in '\u0980'..'\u09FF' }
        val locale = if (bangla) Locale("bn", "BD") else Locale.US
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.setLanguage(locale)
        }
        engine.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun addMessage(role: Role, text: String) {
        messages.add(ChatMessage(role, text))
        if (messages.size > 200) messages.removeAt(0)
    }
}
