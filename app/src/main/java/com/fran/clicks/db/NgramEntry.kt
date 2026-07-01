package com.fran.clicks.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "ngrams",
    primaryKeys = ["prefix", "word"],
    indices = [Index(value = ["prefix"])]
)
data class NgramEntry(
    val prefix: String,
    val word: String,
    val count: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)
