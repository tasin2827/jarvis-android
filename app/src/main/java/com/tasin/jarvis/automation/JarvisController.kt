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
    var statusLine by mutableStateOf("Listening for 'Jarvis'...")
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

    // সব ধরনের উচ্চারণ সাপোর্ট
    private val wakeWords = listOf(
        "jarvis", "jarvice", "jarvish", "jar vis", "jar viz", "charvis",
        "জারভিস", "জার্ভিস", "জারভিচ", "জারভীস", "জারবিজ",
        "hey jarvis", "hi jarvis", "হে জারভিস", "ওহে জারভিস"
    )

    // কথা থামানোর কমান্ড (Mute/Stop/চুপ)
    private val muteWords = listOf(
        "mute", "stop", "quiet", "shut up", "মিউট", "চুপ", "চুপ কর", "চুপ করো", "থামো", "থাম", "বন্ধ করো", "বন্ধ কর"
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
        
        model = if (settings.model.contains("3.6") || settings.model.isEmpty()) "gemini-2.0-flash" else settings.model
        hasKey = settings.apiKey.isNotBlank()

        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            // চালু হওয়ার পর একা একা কথা বলবে না, শুধু মাইক সাইলেন্টলি অন হবে
            startContinuousListening()
        }
        state = JarvisState.IDLE
        statusLine = "Listening for 'Jarvis'..."
    }

    // ---------------- Continuous Voice Detection ----------------

    fun startContinuousListening() {
        if (!initialized) return
        startListeningInternal()
    }

    fun startListening() {
        startContinuousListening()
    }

    fun stopListening() {
        stopListeningInternal()
    }

    private fun startListeningInternal() {
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
            restartListening(1000L)
        }
    }

    private fun stopListeningInternal() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (e: Exception) {}
    }

    private fun restartListening(delayMs: Long = 400L) {
        scope.launch {
            delay(delayMs)
            if (state != JarvisState.EXECUTING && state != JarvisState.THINKING) {
                startListeningInternal()
            }
        }
    }

    // তৎক্ষণাৎ কথা বন্ধ করা (Instant Silence)
    private fun silenceImmediately() {
        tts?.stop()
        job?.cancel()
        confirmation?.complete(false)
        pendingConfirmation = null
        heard = ""
        state = JarvisState.IDLE
        statusLine = "Muted. Listening..."
        restartListening(500L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        // কথার মাঝখানে 'mute' বা 'চুপ' বললে তৎক্ষণাৎ ধরে ফেলবে
        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase()
            if (!p.isNullOrBlank()) {
                heard = p
                if (muteWords.any { p.contains(it) }) {
                    silenceImmediately()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            heard = ""
            if (text.isNullOrBlank()) {
                restartListening(300L)
                return
            }
            val lower = text.lowercase()

            // ১. কেউ Mute / Stop / চুপ বললে সাথে সাথে থামবে
            if (muteWords.any { lower.contains(it) }) {
                silenceImmediately()
                return
            }

            // ২. Jarvis বলে ডেকেছে কিনা পরীক্ষা
            val matchedWake = wakeWords.firstOrNull { lower.startsWith(it) || lower.contains(it) }

            if (matchedWake != null) {
                // শুধু জারভিস ডাকলে কমান্ড আলাদা করা
                val command = lower.substringAfter(matchedWake).trim().trim(',', '.', '!', ' ')
                if (command.isEmpty()) {
                    say("Yes Boss, বলুন?")
                } else {
                    submit(command)
                }
            } else {
                // Jarvis না ডাকলে সম্পূর্ণ চুপ থাকবে! কোনো কথা বলবে না
                restartListening(200L)
            }
        }

        override fun onError(error: Int) {
            heard = ""
            // কোনো এরর হলেও একা একা কোনো সাউন্ড করবে না
            restartListening(500L)
        }
    }

    // ---------------- Input & Execution ----------------

    fun submit(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || !initialized) return
        addMessage(Role.USER, text)
        if (LocalCommandParser.isStop(text)) {
            silenceImmediately()
            return
        }
        job = scope.launch { runRequest(text) }
    }

    fun cancel() {
        silenceImmediately()
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
                say("Accessibility বন্ধ আছে, অন করুন।")
                finish(JarvisState.ERROR, "Accessibility is off")
                restartListening(2000L)
                return
            }
            state = JarvisState.EXECUTING
            runSteps(plan.steps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(JarvisState.ERROR, "Error")
            restartListening(1000L)
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
            say("Settings এ Gemini API key দিন।")
            finish(JarvisState.IDLE, "Ready")
            return null
        }
        addMessage(Role.SYSTEM, "Asking Gemini...")
        val provider: AiProvider = GeminiProvider(key, model)
        val result = provider.plan(text, JarvisAccessibilityService.instance?.foregroundPackage())
        val plan = result.getOrNull()
        if (plan == null) {
            finish(JarvisState.ERROR, "AI error")
            return null
        }
        return plan
    }

    private suspend fun runSteps(steps: List<Step>) {
        for ((index, step) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val svc = JarvisAccessibilityService.instance ?: break
            statusLine = "Step ${index + 1}/${steps.size}"

            if (needsConfirmation(step, svc)) {
                val approved = askConfirmation("কাজটি কি করব?")
                if (!approved) {
                    say("বাতিল করা হলো।")
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
        restartListening(1000L)
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

    private fun finish(newState: JarvisState, status: String) {
        state = newState
        statusLine = status
    }

    fun say(text: String) {
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
        val params = Bundle()
        engine.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, params, "jarvis_speech")
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
        addMessage(Role.SYSTEM, "Microphone permission required.")
    }
}
