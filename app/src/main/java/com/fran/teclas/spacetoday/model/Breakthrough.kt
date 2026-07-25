package com.fran.teclas.spacetoday.model

data class Breakthrough(
    val fromSpace: String,
    val fromSpaceName: String,
    val who: String,
    val text: String,
    val timeCritical: Boolean,
    val signalRef: String
)

