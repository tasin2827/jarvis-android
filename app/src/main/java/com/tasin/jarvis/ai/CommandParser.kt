package com.tasin.jarvis.ai

import com.tasin.jarvis.domain.ActionType
import com.tasin.jarvis.domain.Plan
import com.tasin.jarvis.domain.Risk
import com.tasin.jarvis.domain.Step
import org.json.JSONArray
import org.json.JSONObject

/** Validates AI output. Anything outside the registered action set is rejected. */
object CommandParser {
    private const val MAX_STEPS = 8
    private const val MAX_TEXT = 500
    private val directions = setOf("up", "down", "left", "right")
    private val settingsTargets = setOf("main", "wifi", "bluetooth", "display", "accessibility")

    fun parse(raw: String, source: String): Result<Plan> = runCatching {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = JSONObject(cleaned)
        val reply = obj.str("reply").orEmpty().take(500)
        val arr = obj.optJSONArray("steps") ?: JSONArray()
        require(arr.length() <= MAX_STEPS) { "The plan had too many steps (${arr.length()})." }

        val steps = ArrayList<Step>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val name = s.str("action").orEmpty().uppercase()
            val action = ActionType.values().firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Unsupported action: $name")
            val app = s.str("app")?.take(MAX_TEXT)
            val text = s.str("text")?.take(MAX_TEXT)
            val dir = s.str("direction")?.lowercase()

            when (action) {
                ActionType.OPEN_APP -> require(!app.isNullOrBlank()) { "OPEN_APP needs an app name." }
                ActionType.CLICK_TEXT -> require(!text.isNullOrBlank()) { "CLICK_TEXT needs a label." }
                ActionType.TYPE_TEXT -> require(!text.isNullOrBlank()) { "TYPE_TEXT needs text." }
                else -> {}
            }

            val direction = if (action == ActionType.SCROLL) {
                val d = dir ?: "down"
                require(d in directions) { "Bad scroll direction: $d" }
                d
            } else null

            val finalText = if (action == ActionType.OPEN_SETTINGS) {
                val t = (text ?: "main").lowercase().replace("-", "").replace(" ", "")
                if (t in settingsTargets) t else "main"
            } else text

            steps.add(Step(action, app, finalText, direction, Risk.of(action, finalText)))
        }
        Plan(reply, steps, source)
    }

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = getString(key).trim()
        return v.ifEmpty { null }
    }
}
