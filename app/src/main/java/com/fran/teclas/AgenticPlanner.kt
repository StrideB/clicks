package com.fran.teclas

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Multi-step planning for the GO button: a free-form command ("text ana i'm late, timer 20 min")
 * becomes an ordered list of (skill, arg) steps drawn ONLY from the enabled skill catalog.
 *
 * When the request is served by the local model, a GBNF grammar built from the catalog makes it
 * impossible to name a skill that doesn't exist or emit malformed JSON. Nano/cloud get the same
 * prompt and are validated by the same catalog check afterwards. Blocking — call off main.
 */
object AgenticPlanner {

    data class Step(val skill: String, val arg: String)

    private const val MAX_STEPS = 4

    /** Plan [query] against [skills]. Null when the model can't map it to any skill. */
    fun plan(prefs: SharedPreferences, query: String, skills: List<String>): List<Step>? {
        if (query.isBlank() || skills.isEmpty()) return null
        val prompt = """You turn a phone command into an ordered plan of skill calls. Multiple actions may be joined by "and", "then" or commas — one step each, max $MAX_STEPS.
Skills: ${skills.joinToString(", ")}
Command: "$query"
Reply ONLY as a JSON array: [{"skill":"<exact skill name from the list>","arg":"<the text/target for that step, may be empty>"}]. If nothing fits, reply []."""
        val out = GeminiClient.generate(
            GeminiClient.apiKey(prefs), GeminiClient.model(prefs), prompt,
            maxTokens = 160, temperature = 0.0, json = true, grammar = planGrammar(skills),
        ) ?: return null
        val start = out.indexOf('[')
        val end = out.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val arr = runCatching { JSONArray(out.substring(start, end + 1)) }.getOrNull() ?: return null
        val byName = skills.associateBy { it.lowercase() }
        val steps = buildList {
            for (i in 0 until minOf(arr.length(), MAX_STEPS)) {
                val o = arr.optJSONObject(i) ?: continue
                val skill = byName[o.optString("skill").trim().lowercase()] ?: continue
                add(Step(skill, o.optString("arg").trim()))
            }
        }
        return steps.ifEmpty { null }
    }

    /** GBNF: a JSON array of steps whose "skill" values are LITERALLY the catalog names. */
    private fun planGrammar(skills: List<String>): String {
        val names = skills.joinToString(" | ") { "\"\\\"${gbnfEscape(it)}\\\"\"" }
        return """
            root ::= "[" ws (step ("," ws step){0,${MAX_STEPS - 1}})? "]" ws
            step ::= "{" ws "\"skill\"" ws ":" ws skillname ws "," ws "\"arg\"" ws ":" ws str ws "}" ws
            skillname ::= $names
            str ::= "\"" ([^"\\] | "\\" (["\\bfnrt])){0,120} "\""
            ws ::= [ \t\n]{0,10}
        """.trimIndent()
    }

    private fun gbnfEscape(s: String): String = s.replace("\\", "").replace("\"", "")
}
