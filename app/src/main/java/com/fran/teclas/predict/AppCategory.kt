package com.fran.teclas.predict

/**
 * Coarse app category used for Space cold-start priors. Richer than Android's
 * ApplicationInfo.category (which only has ~9 values and is often undefined), because a
 * Space like Work cares about COMMUNICATION vs PRODUCTIVITY vs FINANCE, distinctions the
 * manifest doesn't draw. Resolved by [AppCategories].
 */
enum class AppCategory {
    COMMUNICATION, PRODUCTIVITY, SOCIAL, MUSIC, VIDEO, PHOTOS, MAPS,
    GAMES, NEWS, HEALTH, FINANCE, SHOPPING, TRAVEL, READING, TOOLS, OTHER
}
