package app.webreader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.webreader.domain.SiteConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsStore(context: Context, dataStoreName: String = "settings") {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.applicationContext.preferencesDataStoreFile(dataStoreName)
        }

    val siteConfig: Flow<SiteConfig> = dataStore.data.map {
        SiteConfig(it[BASE_URL] ?: "", it[ALIAS_HOSTS] ?: emptySet())
    }

    suspend fun setBaseUrl(url: String) {
        dataStore.edit { it[BASE_URL] = url }
    }

    suspend fun setAliasHosts(hosts: Set<String>) {
        dataStore.edit { it[ALIAS_HOSTS] = hosts }
    }

    val seriesPattern: Flow<String> = dataStore.data.map { it[SERIES_PATTERN] ?: "" }
    val episodePattern: Flow<String> = dataStore.data.map { it[EPISODE_PATTERN] ?: "" }

    suspend fun setPatterns(series: String, episode: String): Result<Unit> {
        val check = runCatching {
            if (series.isNotEmpty()) Regex(series)
            if (episode.isNotEmpty()) Regex(episode)
        }
        check.exceptionOrNull()?.let { return Result.failure(it) }
        dataStore.edit {
            it[SERIES_PATTERN] = series
            it[EPISODE_PATTERN] = episode
        }
        return Result.success(Unit)
    }

    val cacheLimitMb: Flow<Int> = dataStore.data.map { it[CACHE_LIMIT_MB] ?: DEFAULT_CACHE_LIMIT_MB }

    suspend fun setCacheLimitMb(mb: Int) {
        dataStore.edit { it[CACHE_LIMIT_MB] = mb }
    }

    val protectFavorites: Flow<Boolean> = dataStore.data.map { it[PROTECT_FAVORITES] ?: true }

    suspend fun setProtectFavorites(v: Boolean) {
        dataStore.edit { it[PROTECT_FAVORITES] = v }
    }

    private companion object {
        const val DEFAULT_CACHE_LIMIT_MB = 500
        val BASE_URL = stringPreferencesKey("base_url")
        val ALIAS_HOSTS = stringSetPreferencesKey("alias_hosts")
        val SERIES_PATTERN = stringPreferencesKey("series_pattern")
        val EPISODE_PATTERN = stringPreferencesKey("episode_pattern")
        val CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
        val PROTECT_FAVORITES = booleanPreferencesKey("protect_favorites")
    }
}
