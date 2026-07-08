package com.fran.clicks.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded "context -> app launch" transition for the prediction engine.
 * [blob] is a Keystore-encrypted JSON payload ({features, contextKey, pkg, source,
 * predicted, reward}) so launch history is unreadable at rest; only the timestamp
 * stays plaintext for pruning. See predict/PredictCrypto.
 */
@Entity(tableName = "app_transitions", indices = [Index("ts")])
data class AppTransitionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val blob: String,
)
