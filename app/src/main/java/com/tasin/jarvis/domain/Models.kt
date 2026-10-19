package com.tasin.jarvis.domain

enum class ActionType {
    OPEN_APP, CLICK_TEXT, TYPE_TEXT, PRESS_ENTER, SCROLL, READ_SCREEN, BACK, HOME, RECENTS, OPEN_SETTINGS
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

data class Step(
    val action: ActionType,
    val app: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val risk: RiskLevel = RiskLevel.LOW,
    val timeoutMs: Long = 3000L
)

data class Plan(val reply: String, val steps: List<Step>, val source: String)

data class StepResult(val ok: Boolean, val verified: Boolean, val message: String)

enum class Role { USER, JARVIS, SYSTEM }

data class ChatMessage(val role: Role, val text: String)

enum class JarvisState { IDLE, LISTENING, THINKING, EXECUTING, DONE, ERROR }

object Risk {
    private val highRiskWords = listOf(
        "send", "pay", "buy", "purchase", "delete", "remove", "call", "dial", "post",
        "publish", "transfer", "order", "confirm", "submit", "checkout", "subscribe",
        "পাঠা", "কিনু", "কিনুন", "মুছ", "কল", "পেমেন্ট", "ডিলিট"
    )
    private val messagingApps = listOf(
        "whatsapp", "telegram", "messenger", "mms", "messaging", "sms", "signal", "viber", "imo", "orca"
    )

    fun of(action: ActionType, text: String?): RiskLevel = when (action) {
        ActionType.TYPE_TEXT, ActionType.PRESS_ENTER -> RiskLevel.MEDIUM
        ActionType.CLICK_TEXT -> {
            val t = text.orEmpty().lowercase()
            if (highRiskWords.any { t.contains(it) }) RiskLevel.HIGH else RiskLevel.MEDIUM
        }
        else -> RiskLevel.LOW
    }

    fun isMessagingPackage(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return messagingApps.any { p.contains(it) }
    }
}
