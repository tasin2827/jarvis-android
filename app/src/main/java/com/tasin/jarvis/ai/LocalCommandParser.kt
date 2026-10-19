package com.tasin.jarvis.ai

import com.tasin.jarvis.domain.ActionType
import com.tasin.jarvis.domain.Plan
import com.tasin.jarvis.domain.Risk
import com.tasin.jarvis.domain.Step

/** Handles simple commands on the phone so nothing is sent to the cloud. Returns null when unsure. */
object LocalCommandParser {
    private val wakeWords = listOf("hey jarvis", "hi jarvis", "jarvis", "হে জারভিস", "জারভিস", "জার্ভিস")
    private val conjunctions = listOf(" and ", " then ", " ar ", " ebong ", " এবং ", " আর ", " তারপর ", " এরপর ")
    private val stopWords = setOf("stop", "cancel", "stop it", "stop jarvis", "cancel it", "থামো", "থাম", "থামাও", "বন্ধ করো", "বন্ধ কর")
    private val backWords = setOf("back", "go back", "পিছনে যাও", "পেছনে যাও", "ফিরে যাও", "পিছনে", "পেছনে")
    private val homeWords = setOf("home", "go home", "go to home", "হোমে যাও", "হোম")
    private val recentsWords = setOf("recents", "recent apps", "open recents", "show recent apps")
    private val readWords = setOf("read screen", "read the screen", "read my screen", "স্ক্রিন পড়ো", "স্ক্রিনে কী আছে")
    private val downWords = setOf("scroll down", "scroll niche", "নিচে যাও", "নিচে স্ক্রল করো", "নিচে")
    private val upWords = setOf("scroll up", "উপরে যাও", "উপরে স্ক্রল করো", "উপরে")
    private val enterWords = setOf("press enter", "enter")

    fun isStop(raw: String): Boolean = stripWake(raw).lowercase() in stopWords

    fun parse(raw: String): Plan? {
        val clean = stripWake(raw)
        val lower = clean.lowercase()
        if (lower.isEmpty()) return null
        if (conjunctions.any { " $lower ".contains(it) }) return null

        return when {
            lower in backWords -> plan("Going back.", step(ActionType.BACK))
            lower in homeWords -> plan("Going to the Home screen.", step(ActionType.HOME))
            lower in recentsWords -> plan("Opening recent apps.", step(ActionType.RECENTS))
            lower in readWords -> plan("Reading the screen.", step(ActionType.READ_SCREEN))
            lower in downWords -> plan("Scrolling down.", step(ActionType.SCROLL, dir = "down"))
            lower in upWords -> plan("Scrolling up.", step(ActionType.SCROLL, dir = "up"))
            lower in enterWords -> plan("Pressing Enter.", step(ActionType.PRESS_ENTER))
            else -> parsePrefixed(clean, lower)
        }
    }

    private fun parsePrefixed(clean: String, lower: String): Plan? {
        argAfter(clean, lower, listOf("open ", "launch ", "start "))?.let { return openApp(it) }
        argBefore(clean, lower, listOf(" kholo", " khulo", " খোলো", " খুলো", " ওপেন করো", " ওপেন কর", " open koro"))
            ?.let { return openApp(it) }
        argAfter(clean, lower, listOf("click ", "tap "))?.let {
            return plan("Tapping $it.", step(ActionType.CLICK_TEXT, text = it))
        }
        argAfter(clean, lower, listOf("type ", "write "))?.let {
            return plan("Typing.", step(ActionType.TYPE_TEXT, text = it))
        }
        return null
    }

    private fun openApp(name: String): Plan? {
        val n = name.trim()
        if (n.isEmpty() || n.split(" ").size > 3) return null
        if (n.any { it.code > 127 }) return null // non-English name: let the cloud AI translate it
        if (n.lowercase() == "settings") {
            return plan("Opening Settings.", step(ActionType.OPEN_SETTINGS, text = "main"))
        }
        return plan("Opening $n.", step(ActionType.OPEN_APP, app = n))
    }

    private fun stripWake(raw: String): String {
        var t = raw.trim().trim('.', '!', '?', '।', ',', ' ')
        val lower = t.lowercase()
        for (w in wakeWords) {
            if (lower == w) return ""
            if (lower.startsWith("$w ") || lower.startsWith("$w,")) {
                t = t.drop(w.length).trim(',', ' ', ':')
                break
            }
        }
        return t
    }

    private fun argAfter(clean: String, lower: String, prefixes: List<String>): String? {
        for (p in prefixes) {
            if (lower.startsWith(p)) return clean.drop(p.length).trim().ifEmpty { null }
        }
        return null
    }

    private fun argBefore(clean: String, lower: String, suffixes: List<String>): String? {
        for (s in suffixes) {
            if (lower.endsWith(s)) return clean.dropLast(s.length).trim().ifEmpty { null }
        }
        return null
    }

    private fun plan(reply: String, vararg steps: Step) = Plan(reply, steps.toList(), "local")

    private fun step(action: ActionType, app: String? = null, text: String? = null, dir: String? = null) =
        Step(action, app, text, dir, Risk.of(action, text))
}
