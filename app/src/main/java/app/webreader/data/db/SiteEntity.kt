package app.webreader.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.webreader.domain.Site
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "site")
data class SiteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val aliasHosts: String,
    val seriesPattern: String,
    val episodePattern: String,
    val createdAt: Long,
) {
    fun toSite() = Site(
        id, name, baseUrl,
        aliasHosts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        seriesPattern, episodePattern,
    )

    companion object {
        fun from(site: Site, createdAt: Long) = SiteEntity(
            site.id, site.name, site.baseUrl,
            site.aliasHosts.joinToString(",") { it.lowercase() },
            site.seriesPattern, site.episodePattern, createdAt,
        )
    }
}

@Dao
interface SiteDao {
    @Query("SELECT * FROM site ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SiteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: SiteEntity)

    @Query("DELETE FROM site WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM site")
    suspend fun count(): Int
}
