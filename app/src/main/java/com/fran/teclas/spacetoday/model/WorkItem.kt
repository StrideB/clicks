package com.fran.teclas.spacetoday.model

import com.fran.teclas.brief.BriefCategory
import com.fran.teclas.brief.BriefItem

data class WorkAction(
    val label: String,
    val kind: String
)

data class WorkItem(
    val signalRef: String,
    val space: String,
    val zone: Zone,
    val category: BriefCategory,
    val who: String,
    val summary: String,
    val time: String,
    val startsInMin: Int?,
    val score: Int,
    val tier: Tier,
    val primaryAction: WorkAction,
    val extraActions: List<WorkAction> = emptyList(),
    val source: BriefItem? = null,
    val contentHash: String = ""
)

