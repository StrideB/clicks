package com.fran.clicks.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One agentic skill: a named capability the space bar can invoke. New skills can be added over time
 * (built-in or user-created) without code changes — the router reads them from this table.
 *
 * [actionType] is one of AgenticRouter.ActionType: MUSIC / TIMER / LOCATION / WEB_SEARCH launch via
 * special intents; URI launches [uriTemplate] with "{q}" replaced by the encoded argument (this is
 * what makes custom skills trivial — most are just "open this URL with my query").
 *
 * [triggers] is a comma-separated list of match patterns:
 *  - "play " (trailing space) → prefix with an argument: "play drake" → arg "drake"
 *  - "* near me"              → suffix: "best buy near me" → arg "best buy"
 *  - "share location"         → exact phrase, no argument
 */
@Entity(tableName = "agentic_skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val actionType: String,
    val uriTemplate: String = "",
    val triggers: String,
    val labelTemplate: String,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val sortOrder: Int = 0,
    val usageCount: Int = 0,
    val lastUsed: Long = 0
)
