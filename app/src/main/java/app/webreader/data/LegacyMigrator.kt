package app.webreader.data

import app.webreader.data.db.FavoriteDao
import app.webreader.data.db.ReadHistoryDao
import kotlinx.coroutines.flow.first

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
        if (config.baseUrl.isNotBlank()) {
            siteRepository.add(
                "Default", config.baseUrl, config.aliasHosts.joinToString(","),
                settings.seriesPattern.first(), settings.episodePattern.first(),
            ).onSuccess { site ->
                favoriteDao.reassignLegacy(site.id)
                historyDao.reassignLegacy(site.id)
                cache.clear()
                settings.setCurrentSiteId(site.id)
            }
        }
        settings.setLegacyMigrated()
    }
}
