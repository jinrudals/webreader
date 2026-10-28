package app.webreader.data

import android.util.Log
import app.webreader.data.db.FavoriteDao
import app.webreader.data.db.ReadHistoryDao
import kotlinx.coroutines.flow.first
import java.net.URI

/** One-time conversion of the single-site settings and un-namespaced records into a "Default" site. */
class LegacyMigrator(
    private val settings: SettingsStore,
    private val siteRepository: SiteRepository,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadHistoryDao,
    private val cache: ResourceCache,
) {
    suspend fun run() {
        if (settings.legacyMigrated.first()) return
        val config = settings.siteConfig.first()
        if (config.baseUrl.isBlank()) {
            settings.setLegacyMigrated()
            return
        }
        val legacyHost = runCatching { URI(config.baseUrl.trim()).host?.lowercase() }.getOrNull()
        val existing = legacyHost?.let { host ->
            siteRepository.sites.first().firstOrNull { site ->
                runCatching { URI(site.baseUrl).host?.lowercase() }.getOrNull() == host
            }
        }
        val site = existing ?: siteRepository.add(
            "Default", config.baseUrl, config.aliasHosts.joinToString(","),
            settings.seriesPattern.first(), settings.episodePattern.first(),
        ).getOrElse {
            Log.w(TAG, "Legacy migration failed; will retry next launch", it)
            return
        }
        favoriteDao.reassignLegacy(site.id)
        historyDao.reassignLegacy(site.id)
        cache.clear()
        settings.setCurrentSiteId(site.id)
        settings.setLegacyMigrated()
    }

    private companion object {
        const val TAG = "LegacyMigrator"
    }
}
