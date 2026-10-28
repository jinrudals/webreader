package app.webreader.data.db

import androidx.room.Entity

@Entity(tableName = "favorite", primaryKeys = ["siteId", "key"])
data class Favorite(
    val siteId: String,
    val key: String,
    val type: String,
    val title: String,
    val addedAt: Long,
) {
    companion object {
        const val TYPE_SERIES = "SERIES"
        const val TYPE_EPISODE = "EPISODE"
    }
}
