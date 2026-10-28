package app.webreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: Favorite)

    @Query("DELETE FROM favorite WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Favorite>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE `key` = :key)")
    fun observeIsFavorite(key: String): Flow<Boolean>

    @Query("SELECT `key` FROM favorite")
    suspend fun allKeys(): List<String>
}

@Dao
interface ReadHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: ReadHistory)

    @Query("SELECT * FROM read_history WHERE `key` = :key")
    suspend fun get(key: String): ReadHistory?

    @Query("SELECT * FROM read_history ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReadHistory>>
}
