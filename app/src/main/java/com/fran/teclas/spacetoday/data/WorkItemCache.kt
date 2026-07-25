package com.fran.teclas.spacetoday.data

import android.content.SharedPreferences
import com.fran.teclas.brief.BriefCategory
import com.fran.teclas.spacetoday.model.SpaceWorkload
import com.fran.teclas.spacetoday.model.Tier
import com.fran.teclas.spacetoday.model.WorkAction
import com.fran.teclas.spacetoday.model.WorkItem
import com.fran.teclas.spacetoday.model.Zone
import org.json.JSONArray
import org.json.JSONObject

/** Text-only cache. Live signals and PendingIntents are intentionally never persisted. */
class WorkItemCache(private val prefs: SharedPreferences) {
    fun save(workloads: Map<String, SpaceWorkload>) {
        val root = JSONObject()
        workloads.forEach { (space, workload) ->
            val items = JSONArray()
            workload.items.forEach { item ->
                items.put(JSONObject()
                    .put("ref", item.signalRef)
                    .put("space", item.space)
                    .put("zone", item.zone.name)
                    .put("category", item.category.name)
                    .put("who", item.who)
                    .put("summary", item.summary)
                    .put("time", item.time)
                    .put("score", item.score)
                    .put("tier", item.tier.name)
                    .put("primaryLabel", item.primaryAction.label)
                    .put("primaryKind", item.primaryAction.kind)
                    .put("hash", item.contentHash))
            }
            root.put(space, JSONObject()
                .put("spaceName", workload.spaceName)
                .put("hasWorkload", workload.hasWorkload)
                .put("hero", workload.hero)
                .put("needNow", workload.stat.needNow)
                .put("tasks", workload.stat.tasks)
                .put("updates", workload.stat.updates)
                .put("items", items))
        }
        prefs.edit().putString(PREF, root.toString()).apply()
    }

    fun load(): Map<String, SpaceWorkload> {
        val raw = prefs.getString(PREF, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { space ->
                val obj = root.optJSONObject(space) ?: JSONObject()
                val arr = obj.optJSONArray("items") ?: JSONArray()
                val items = (0 until arr.length()).mapNotNull { idx ->
                    val i = arr.optJSONObject(idx) ?: return@mapNotNull null
                    WorkItem(
                        signalRef = i.optString("ref"),
                        space = i.optString("space", space),
                        zone = runCatching { Zone.valueOf(i.optString("zone")) }.getOrDefault(Zone.SYSTEM),
                        category = runCatching { BriefCategory.valueOf(i.optString("category")) }.getOrDefault(BriefCategory.OTHER),
                        who = i.optString("who"),
                        summary = i.optString("summary"),
                        time = i.optString("time"),
                        startsInMin = null,
                        score = i.optInt("score"),
                        tier = runCatching { Tier.valueOf(i.optString("tier")) }.getOrDefault(Tier.T3_AMBIENT),
                        primaryAction = WorkAction(i.optString("primaryLabel", "Open"), i.optString("primaryKind", "open")),
                        contentHash = i.optString("hash")
                    )
                }
                SpaceWorkload(
                    spaceId = space,
                    spaceName = obj.optString("spaceName", space.replaceFirstChar { it.uppercase() }),
                    hasWorkload = obj.optBoolean("hasWorkload", true),
                    hero = obj.optString("hero"),
                    stat = SpaceWorkload.Stat(obj.optInt("needNow"), obj.optInt("tasks"), obj.optInt("updates")),
                    items = items
                )
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PREF = "space_today_cache"
    }
}
