package app.webreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class FavoriteRef(val siteId: String, val key: String)

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: Favorite)

    @Query("DELETE FROM favorite WHERE siteId = :siteId AND `key` = :key")
    suspend fun delete(siteId: String, key: String)

    @Query("SELECT * FROM favorite WHERE siteId = :siteId ORDER BY addedAt DESC")
    fun observeAll(siteId: String): Flow<List<Favorite>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE siteId = :siteId AND `key` = :key)")
    fun observeIsFavorite(siteId: String, key: String): Flow<Boolean>

    @Query("SELECT siteId, `key` FROM favorite")
    fun observeAllRefs(): Flow<List<FavoriteRef>>

    @Query("DELETE FROM favorite WHERE siteId = :siteId")
    suspend fun deleteForSite(siteId: String)

    @Query("UPDATE favorite SET siteId = :siteId WHERE siteId = ''")
    suspend fun reassignLegacy(siteId: String)
}

@Dao
interface ReadHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: ReadHistory)

    @Query("SELECT * FROM read_history WHERE siteId = :siteId AND `key` = :key")
    suspend fun get(siteId: String, key: String): ReadHistory?

    @Query("SELECT * FROM read_history WHERE siteId = :siteId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(siteId: String, limit: Int): Flow<List<ReadHistory>>

    @Query("DELETE FROM read_history WHERE siteId = :siteId")
    suspend fun deleteForSite(siteId: String)

    @Query("UPDATE read_history SET siteId = :siteId WHERE siteId = ''")
    suspend fun reassignLegacy(siteId: String)
}
