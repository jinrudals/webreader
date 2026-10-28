package app.webreader.data.db

import androidx.room.Entity

@Entity(tableName = "read_history", primaryKeys = ["siteId", "key"])
data class ReadHistory(
    val siteId: String,
    val key: String,
    val lastPosition: Int,
    val updatedAt: Long,
)
