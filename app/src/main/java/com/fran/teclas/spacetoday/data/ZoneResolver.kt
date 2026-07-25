package com.fran.teclas.spacetoday.data

import android.content.SharedPreferences
import com.fran.teclas.spacetoday.model.Zone
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Resolves source app/account into a broad zone once at ingest. Overrides live in the existing
 * `teclas` SharedPreferences and use package/account keys.
 */
class ZoneResolver(private val prefs: SharedPreferences) {
    fun resolve(packageOrAccount: String, accountEmail: String? = null, appLabel: String? = null): Zone {
        val key = packageOrAccount.trim().lowercase(Locale.US)
        overrideFor(key)?.let { return it }
        accountEmail?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { account ->
            overrideFor(account)?.let { return it }
            if (workDomains().any { domain -> account.endsWith("@$domain") || account.endsWith(domain) }) return Zone.WORK
            if (account.contains("gmail") || account.contains("@")) return Zone.PERSONAL
        }

        val haystack = listOf(packageOrAccount, appLabel.orEmpty()).joinToString(" ").lowercase(Locale.US)
        return when {
            WORK_HINTS.any { haystack.contains(it) } -> Zone.WORK
            PERSONAL_HINTS.any { haystack.contains(it) } -> Zone.PERSONAL
            SYSTEM_HINTS.any { haystack.contains(it) } -> Zone.SYSTEM
            haystack.contains("mail") || haystack.contains("message") -> Zone.PERSONAL
            else -> Zone.SYSTEM
        }
    }

    fun setOverride(packageOrAccount: String, zone: Zone) {
        val obj = JSONObject(prefs.getString(PREF_OVERRIDES, "{}") ?: "{}")
        obj.put(packageOrAccount.trim().lowercase(Locale.US), zone.name)
        prefs.edit().putString(PREF_OVERRIDES, obj.toString()).apply()
    }

    fun workDomains(): Set<String> {
        val raw = prefs.getString(PREF_WORK_DOMAINS, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it).trim().lowercase(Locale.US).removePrefix("@") }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun overrideFor(key: String): Zone? {
        val raw = prefs.getString(PREF_OVERRIDES, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            Zone.valueOf(obj.optString(key).uppercase(Locale.US))
        }.getOrNull()
    }

    private companion object {
        const val PREF_OVERRIDES = "zone_overrides_json"
        const val PREF_WORK_DOMAINS = "zone_work_domains_json"
        val WORK_HINTS = listOf("slack", "linear", "notion", "teams", "outlook", "asana", "jira", "github", "docs", "sheets")
        val PERSONAL_HINTS = listOf("whatsapp", "telegram", "signal", "messages", "messenger", "instagram", "facebook", "snapchat", "gmail")
        val SYSTEM_HINTS = listOf("calendar", "bank", "capital", "cash app", "authenticator", "delivery", "carrier", "maps", "uber", "lyft")
    }
}
