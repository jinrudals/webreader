package app.webreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.webreader.AppContainer
import app.webreader.data.ResourceCache
import app.webreader.data.SettingsStore
import app.webreader.data.SiteRepository
import app.webreader.domain.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Site validation lives in [SiteRepository]; cache settings are global. */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val cache: ResourceCache,
    private val siteRepository: SiteRepository,
) : ViewModel() {

    val sites: Flow<List<Site>> = siteRepository.sites
    val cacheLimitMb: Flow<Int> = settings.cacheLimitMb
    val protectFavorites: Flow<Boolean> = settings.protectFavorites

    /** Polled while collected; the limit itself is applied by AppContainer observing settings. */
    val cacheSizeBytes: Flow<Long> = flow {
        while (true) {
            emit(cache.sizeBytes())
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun addSite(name: String, baseUrl: String, aliases: String, series: String, episode: String): Result<Site> =
        siteRepository.add(name, baseUrl, aliases, series, episode)

    suspend fun updateSite(
        id: String, name: String, baseUrl: String, aliases: String, series: String, episode: String,
    ): Result<Unit> = siteRepository.update(id, name, baseUrl, aliases, series, episode)

    suspend fun deleteSite(id: String) = siteRepository.delete(id)

    suspend fun saveCache(limitMb: Int, protectFavorites: Boolean) {
        settings.setCacheLimitMb(limitMb.coerceAtLeast(1))
        settings.setProtectFavorites(protectFavorites)
    }

    /** Removes every entry, including those of favorites. */
    fun clearCache(): Job = viewModelScope.launch { withContext(Dispatchers.IO) { cache.clear() } }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container.settings, container.resourceCache, container.siteRepository) as T
    }

    private companion object {
        const val POLL_MS = 2000L
    }
}
