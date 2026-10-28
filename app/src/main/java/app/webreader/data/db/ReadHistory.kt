package app.webreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "read_history")
data class ReadHistory(
    @PrimaryKey val key: String,
    val lastPosition: Int,
    val updatedAt: Long,
)
