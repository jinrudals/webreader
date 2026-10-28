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
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import kotlinx.coroutines.flow.map

class SettingsStore(context: Context, dataStoreName: String = "settings") {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) {
            context.applicationContext.preferencesDataStoreFile(dataStoreName)
        }

    private val safeData: Flow<Preferences> = dataStore.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }

    val siteConfig: Flow<SiteConfig> = safeData.map {
        SiteConfig(it[BASE_URL] ?: "", it[ALIAS_HOSTS] ?: emptySet())
    }

    suspend fun setBaseUrl(url: String) {
        dataStore.edit { it[BASE_URL] = url }
    }

    suspend fun setAliasHosts(hosts: Set<String>) {
        dataStore.edit { it[ALIAS_HOSTS] = hosts }
    }

    val seriesPattern: Flow<String> = safeData.map { it[SERIES_PATTERN] ?: "" }
    val episodePattern: Flow<String> = safeData.map { it[EPISODE_PATTERN] ?: "" }

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

    val cacheLimitMb: Flow<Int> = safeData.map { it[CACHE_LIMIT_MB] ?: DEFAULT_CACHE_LIMIT_MB }

    suspend fun setCacheLimitMb(mb: Int) {
        dataStore.edit { it[CACHE_LIMIT_MB] = mb }
    }

    val protectFavorites: Flow<Boolean> = safeData.map { it[PROTECT_FAVORITES] ?: true }

    suspend fun setProtectFavorites(v: Boolean) {
        dataStore.edit { it[PROTECT_FAVORITES] = v }
    }

    val currentSiteId: Flow<String?> = safeData.map { it[CURRENT_SITE_ID] }

    suspend fun setCurrentSiteId(id: String) {
        dataStore.edit { it[CURRENT_SITE_ID] = id }
    }

    val legacyMigrated: Flow<Boolean> = safeData.map { it[LEGACY_MIGRATED] ?: false }

    suspend fun setLegacyMigrated() {
        dataStore.edit { it[LEGACY_MIGRATED] = true }
    }

    private companion object {
        val CURRENT_SITE_ID = stringPreferencesKey("current_site_id")
        val LEGACY_MIGRATED = booleanPreferencesKey("legacy_migrated")
        const val DEFAULT_CACHE_LIMIT_MB = 500
        val BASE_URL = stringPreferencesKey("base_url")
        val ALIAS_HOSTS = stringSetPreferencesKey("alias_hosts")
        val SERIES_PATTERN = stringPreferencesKey("series_pattern")
        val EPISODE_PATTERN = stringPreferencesKey("episode_pattern")
        val CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
        val PROTECT_FAVORITES = booleanPreferencesKey("protect_favorites")
    }
}
