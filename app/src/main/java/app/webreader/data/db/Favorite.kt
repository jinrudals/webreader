package app.webreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite")
data class Favorite(
    @PrimaryKey val key: String,
    val type: String,
    val title: String,
    val addedAt: Long,
) {
    companion object {
        const val TYPE_SERIES = "SERIES"
        const val TYPE_EPISODE = "EPISODE"
    }
}
