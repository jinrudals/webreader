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

    // Legacy single-site keys: read only, consumed by LegacyMigrator.
    val siteConfig: Flow<SiteConfig> = safeData.map {
        SiteConfig(it[BASE_URL] ?: "", it[ALIAS_HOSTS] ?: emptySet())
    }

    val seriesPattern: Flow<String> = safeData.map { it[SERIES_PATTERN] ?: "" }
    val episodePattern: Flow<String> = safeData.map { it[EPISODE_PATTERN] ?: "" }

    /** Writes the pre-multi-site keys so migration can be tested; production never writes them. */
    internal suspend fun seedLegacySite(
        baseUrl: String? = null, aliasHosts: Set<String>? = null, series: String? = null, episode: String? = null,
    ) {
        dataStore.edit {
            baseUrl?.let { v -> it[BASE_URL] = v }
            aliasHosts?.let { v -> it[ALIAS_HOSTS] = v }
            series?.let { v -> it[SERIES_PATTERN] = v }
            episode?.let { v -> it[EPISODE_PATTERN] = v }
        }
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
