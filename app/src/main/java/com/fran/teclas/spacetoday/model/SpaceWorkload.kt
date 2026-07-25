package com.fran.teclas.spacetoday.model

data class SpaceWorkload(
    val spaceId: String,
    val spaceName: String,
    val hasWorkload: Boolean,
    val hero: String,
    val stat: Stat,
    val items: List<WorkItem>
) {
    data class Stat(val needNow: Int, val tasks: Int, val updates: Int)

    companion object {
        fun ambient(spaceId: String, spaceName: String) =
            SpaceWorkload(spaceId, spaceName, hasWorkload = false, hero = "", stat = Stat(0, 0, 0), items = emptyList())
    }
}

