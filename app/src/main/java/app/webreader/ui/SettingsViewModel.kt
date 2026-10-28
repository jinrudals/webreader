package app.webreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.webreader.AppContainer
import app.webreader.data.ResourceCache
import app.webreader.data.SettingsStore
import app.webreader.domain.SiteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** Validates and normalizes input; [SettingsStore] itself is lenient. */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val cache: ResourceCache,
) : ViewModel() {

    val siteConfig: Flow<SiteConfig> = settings.siteConfig
    val seriesPattern: Flow<String> = settings.seriesPattern
    val episodePattern: Flow<String> = settings.episodePattern
    val cacheLimitMb: Flow<Int> = settings.cacheLimitMb
    val protectFavorites: Flow<Boolean> = settings.protectFavorites

    /** Polled while collected; the limit itself is applied by AppContainer observing settings. */
    val cacheSizeBytes: Flow<Long> = flow {
        while (true) {
            emit(cache.sizeBytes())
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun saveBaseUrl(url: String): Result<Unit> {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Enter a valid http(s) URL"))
        }
        val rest = trimmed.substring(trimmed.indexOf("://") + 3)
        val normalized = "$scheme://" + rest.replaceFirst(Regex(Regex.escape(uri!!.host), RegexOption.IGNORE_CASE), host)
        val config = settings.siteConfig.first()
        val oldHost = runCatching { URI(config.baseUrl).host }.getOrNull()?.lowercase()
        if (!oldHost.isNullOrBlank() && oldHost != host) {
            settings.setAliasHosts((config.aliasHosts + oldHost).map { it.lowercase() }.toSet() - host)
        }
        settings.setBaseUrl(normalized)
        return Result.success(Unit)
    }

    suspend fun saveAliasHosts(text: String) {
        val hosts = text.split(Regex("[,\\s]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        settings.setAliasHosts(hosts)
    }

    suspend fun savePatterns(series: String, episode: String): Result<Unit> =
        settings.setPatterns(series.trim(), episode.trim())

    suspend fun saveCache(limitMb: Int, protectFavorites: Boolean) {
        settings.setCacheLimitMb(limitMb.coerceAtLeast(1))
        settings.setProtectFavorites(protectFavorites)
    }

    /** Removes every entry, including those of favorites. */
    fun clearCache(): Job = viewModelScope.launch { withContext(Dispatchers.IO) { cache.clear() } }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container.settings, container.resourceCache) as T
    }

    private companion object {
        const val POLL_MS = 2000L
    }
}
