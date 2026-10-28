package app.webreader

import android.content.Context
import app.webreader.data.ResourceCache
import app.webreader.data.SettingsStore
import app.webreader.data.db.AppDatabase
import app.webreader.domain.PageClassifier
import app.webreader.domain.SiteConfig
import app.webreader.domain.UrlKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

/** Manual wiring. Lambdas handed to the WebView path read volatile snapshots and never block. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.build(appContext)
    val settings = SettingsStore(appContext)
    val http: OkHttpClient = OkHttpClient()

    private val initialSiteConfig: SiteConfig = runBlocking { settings.siteConfig.first() }
    private val initialClassifier: PageClassifier =
        runBlocking { PageClassifier(settings.seriesPattern.first(), settings.episodePattern.first()) }
    private val initialProtectedKeys: Set<String> = runBlocking { currentProtectedKeys() }

    private val _siteConfigFlow = MutableStateFlow(initialSiteConfig)
    private val _classifierFlow = MutableStateFlow(initialClassifier)

    /** Observable site config for UI; the request path uses [siteConfig]. */
    val siteConfigFlow: StateFlow<SiteConfig> = _siteConfigFlow.asStateFlow()
    val classifierFlow: StateFlow<PageClassifier> = _classifierFlow.asStateFlow()

    @Volatile private var siteConfigSnapshot: SiteConfig = initialSiteConfig
    @Volatile private var cacheLimitBytes: Long = 0L
    @Volatile private var protectedKeys: Set<String> = initialProtectedKeys
    @Volatile private var classifierSnapshot: PageClassifier = initialClassifier

    val siteConfig: () -> SiteConfig = { siteConfigSnapshot }
    val classifier: () -> PageClassifier = { classifierSnapshot }
    val keyer = UrlKeyer(siteConfig)

    val resourceCache = ResourceCache(
        File(appContext.filesDir, "resource-cache"),
        { cacheLimitBytes },
        { protectedKeys },
    )

    init {
        cacheLimitBytes = (runBlocking { settings.cacheLimitMb.first() }.toLong() * MB).coerceAtLeast(0)
        settings.siteConfig.onEach { siteConfigSnapshot = it; _siteConfigFlow.value = it }.launchIn(appScope)
        settings.cacheLimitMb.onEach { cacheLimitBytes = (it.toLong() * MB).coerceAtLeast(0) }.launchIn(appScope)
        combine(settings.seriesPattern, settings.episodePattern) { s, e -> PageClassifier(s, e) }
            .onEach { classifierSnapshot = it; _classifierFlow.value = it }.launchIn(appScope)
        combine(settings.protectFavorites, database.favoriteDao().observeAll()) { protect, favs ->
            protectedSet(protect, favs.map { it.key })
        }.onEach { protectedKeys = it }.launchIn(appScope)
    }

    private suspend fun currentProtectedKeys(): Set<String> =
        protectedSet(settings.protectFavorites.first(), database.favoriteDao().observeAll().first().map { it.key })

    private fun protectedSet(protect: Boolean, keys: List<String>): Set<String> =
        if (protect) keys.toHashSet() else emptySet()

    private companion object {
        const val MB = 1024L * 1024
    }
}
