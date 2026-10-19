package com.tasin.jarvis.automation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    var statusLine by mutableStateOf("Ready")
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
    var model by mutableStateOf("gemini-2.0-flash")
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
    private var isSpeaking = false

    private val wakeWords = listOf("jarvis", "hey jarvis", "hi jarvis", "জারভিস", "হে জারভিস", "জার্ভিস")

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settings = JarvisSettings(appContext)
        executor = StepExecutor(appContext)
        language = settings.language
        cloudEnabled = settings.cloudEnabled
        voiceReplies = settings.voiceReplies
        
        model = if (settings.model.contains("3.6") || settings.model.isEmpty()) "gemini-2.0-flash" else settings.model
        hasKey = settings.apiKey.isNotBlank()

        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                setupTtsListener()
                say("JARVIS online. Say Jarvis anytime.")
            }
        }
        state = JarvisState.IDLE
        statusLine = "Listening for 'Jarvis'..."
    }

    private fun setupTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                stopListeningInternal()
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                restartListening(400L)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                restartListening(400L)
            }
        })
    }

    fun startContinuousListening() {
        if (!initialized || isSpeaking) return
        startListeningInternal()
    }

    fun startListening() {
        startContinuousListening()
    }

    fun stopListening() {
        stopListeningInternal()
    }

    private fun startListeningInternal() {
        if (isSpeaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return

        try {
            recognizer?.destroy()
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
            statusLine = "Listening for 'Jarvis'..."
            r.startListening(intent)
        } catch (e: Exception) {
            restartListening(1200L)
        }
    }

    private fun stopListeningInternal() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (e: Exception) {}
    }

    private fun restartListening(delayMs: Long = 500L) {
        if (isSpeaking) return
        scope.launch {
            delay(delayMs)
            if (state != JarvisState.EXECUTING && state != JarvisState.THINKING && !isSpeaking) {
                startListeningInternal()
            }
        }
    }

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
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            heard = ""
            if (!text.isNullOrBlank()) {
                handleHeardSpeech(text)
            } else {
                restartListening(400L)
            }
        }

        override fun onError(error: Int) {
            heard = ""
            restartListening(600L)
        }
    }

    private fun handleHeardSpeech(text: String) {
        val lower = text.lowercase()
        val matchedWake = wakeWords.firstOrNull { lower.startsWith(it) || lower.contains(it) }

        if (matchedWake != null) {
            val command = lower.substringAfter(matchedWake).trim().trim(',', '.', '!', ' ')
            if (command.isEmpty()) {
                say("Yes Boss? How can I help?")
            } else {
                submit(command)
            }
        } else {
            restartListening(300L)
        }
    }

    fun submit(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || !initialized) return
        addMessage(Role.USER, text)
        if (LocalCommandParser.isStop(text)) {
            cancelCurrent("Stopped.")
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

    private suspend fun runRequest(text: String) {
        try {
            state = JarvisState.THINKING
            statusLine = "Thinking..."
            val plan = obtainPlan(text) ?: run {
                restartListening(1000L)
                return
            }

            if (plan.reply.isNotBlank()) say(plan.reply)
            if (plan.steps.isEmpty()) {
                finish(JarvisState.DONE, "Ready")
                restartListening(1000L)
                return
            }
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                say("Accessibility is off. Please enable it in Settings.")
                finish(JarvisState.ERROR, "Accessibility is off")
                restartListening(2000L)
                return
            }
            state = JarvisState.EXECUTING
            runSteps(plan.steps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("Something went wrong.")
            finish(JarvisState.ERROR, "Error")
            restartListening(1500L)
        }
    }

    private suspend fun obtainPlan(text: String): Plan? {
        val local = LocalCommandParser.parse(text)
        if (local != null) {
            addMessage(Role.SYSTEM, "Handled locally.")
            return local
        }
        val key = settings.apiKey
        if (key.isBlank()) {
            say("Please add your Gemini API key in Settings.")
            finish(JarvisState.IDLE, "Ready")
            return null
        }
        addMessage(Role.SYSTEM, "Asking Gemini...")
        val provider: AiProvider = GeminiProvider(key, model)
        val result = provider.plan(text, JarvisAccessibilityService.instance?.foregroundPackage())
        val plan = result.getOrNull()
        if (plan == null) {
            val reason = result.exceptionOrNull()?.message ?: "AI error."
            say("Couldn't process that: $reason")
            finish(JarvisState.ERROR, "AI error")
            return null
        }
        return plan
    }

    private suspend fun runSteps(steps: List<Step>) {
        for ((index, step) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val svc = JarvisAccessibilityService.instance ?: break
            statusLine = "Executing step ${index + 1}/${steps.size}"

            if (needsConfirmation(step, svc)) {
                val approved = askConfirmation("Should I proceed?")
                if (!approved) {
                    say("Cancelled.")
                    break
                }
            }

            val result = executor.execute(step)
            if (step.action == ActionType.READ_SCREEN && result.ok) {
                speak(result.message.take(300))
            }
            if (!result.ok) break
            delay(500)
        }
        finish(JarvisState.DONE, "Ready")
        restartListening(1200L)
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
            withTimeoutOrNull(30_000L) { d.await() } ?: false
        } finally {
            pendingConfirmation = null
            confirmation = null
        }
    }

    private fun cancelCurrent(message: String) {
        recognizer?.cancel()
        tts?.stop()
        job?.cancel()
        confirmation?.complete(false)
        pendingConfirmation = null
        heard = ""
        finish(JarvisState.IDLE, "Ready")
        say(message)
    }

    private fun finish(newState: JarvisState, status: String) {
        state = newState
        statusLine = status
    }

    fun say(text: String) {
        addMessage(Role.JARVIS, text)
        speak(text)
    }

    private fun speak(text: String) {
        if (!ttsReady || !voiceReplies) {
            restartListening(500L)
            return
        }
        val engine = tts ?: return
        val bangla = text.any { it in '\u0980'..'\u09FF' }
        val locale = if (bangla) Locale("bn", "BD") else Locale.US
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.setLanguage(locale)
        }
        val params = Bundle()
        engine.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, params, "jarvis_msg")
    }

    private fun addMessage(role: Role, text: String) {
        messages.add(ChatMessage(role, text))
        if (messages.size > 200) messages.removeAt(0)
    }

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
    }

    fun clearHistory() {
        messages.clear()
    }

    fun toggleLanguage() {
        language = if (language == "bn-BD") "en-US" else "bn-BD"
        settings.language = language
    }

    fun onMicDenied() {
        addMessage(Role.SYSTEM, "Microphone permission is required for voice activation.")
    }
}
