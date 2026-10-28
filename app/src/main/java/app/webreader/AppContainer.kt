package app.webreader

import android.content.Context
import app.webreader.data.ResourceCache
import app.webreader.data.LegacyMigrator
import app.webreader.data.SettingsStore
import app.webreader.data.SiteRepository
import app.webreader.data.db.AppDatabase
import app.webreader.data.db.FavoriteRef
import app.webreader.domain.PageClassifier
import app.webreader.domain.Site
import app.webreader.domain.SiteConfig
import app.webreader.domain.SiteRegistry
import app.webreader.domain.UrlKeyer
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

/** Manual wiring. Lambdas handed to the WebView path read volatile snapshots and never block. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("AppContainer", "appScope failure", e) },
    )

    val database: AppDatabase = AppDatabase.build(appContext)
    val settings = SettingsStore(appContext)
    val http: OkHttpClient = OkHttpClient()

    @Volatile private var cacheLimitBytes: Long = 0L
    @Volatile private var protectedKeys: Set<String> = emptySet()

    val resourceCache = ResourceCache(
        File(appContext.noBackupFilesDir, "resource-cache"),
        { cacheLimitBytes },
        { protectedKeys },
    )

    val siteRepository = SiteRepository(
        database.siteDao(), database.favoriteDao(), database.readHistoryDao(), resourceCache,
    )

    init {
        runBlocking {
            LegacyMigrator(settings, siteRepository, database.favoriteDao(), database.readHistoryDao(), resourceCache).run()
        }
    }

    private val initialSites: List<Site> = runBlocking { siteRepository.sites.first() }
    private val initialSavedSiteId: String? = runBlocking { settings.currentSiteId.first() }

    val registry: StateFlow<SiteRegistry> = siteRepository.sites.map { SiteRegistry(it) }
        .stateIn(appScope, SharingStarted.Eagerly, SiteRegistry(initialSites))
    @Volatile private var registryRef: SiteRegistry = registry.value
    val registrySnapshot: () -> SiteRegistry = { registryRef }

    private val initialCurrent: Site? = SiteRegistry.resolveCurrent(initialSites, initialSavedSiteId)
    val currentSite: StateFlow<Site?> =
        combine(siteRepository.sites, settings.currentSiteId) { sites, saved -> SiteRegistry.resolveCurrent(sites, saved) }
            .stateIn(appScope, SharingStarted.Eagerly, initialCurrent)
    @Volatile private var currentSiteIdRef: String = initialCurrent?.id ?: ""
    val currentSiteId: () -> String = { currentSiteIdRef }

    suspend fun selectSite(id: String) = settings.setCurrentSiteId(id)

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
    @Volatile private var classifierSnapshot: PageClassifier = initialClassifier

    val siteConfig: () -> SiteConfig = { siteConfigSnapshot }
    val classifier: () -> PageClassifier = { classifierSnapshot }
    val keyer = UrlKeyer()

    init {
        protectedKeys = initialProtectedKeys
        registry.onEach { registryRef = it }.launchIn(appScope)
        currentSite.onEach { currentSiteIdRef = it?.id ?: "" }.launchIn(appScope)
        cacheLimitBytes = (runBlocking { settings.cacheLimitMb.first() }.toLong() * MB).coerceAtLeast(0)
        settings.siteConfig.onEach { siteConfigSnapshot = it; _siteConfigFlow.value = it }.launchIn(appScope)
        settings.cacheLimitMb.onEach { cacheLimitBytes = (it.toLong() * MB).coerceAtLeast(0) }.launchIn(appScope)
        combine(settings.seriesPattern, settings.episodePattern) { s, e -> PageClassifier(s, e) }
            .onEach { classifierSnapshot = it; _classifierFlow.value = it }.launchIn(appScope)
        combine(settings.protectFavorites, database.favoriteDao().observeAllRefs()) { protect, favs ->
            protectedSet(protect, favs)
        }.onEach { protectedKeys = it }.launchIn(appScope)
    }

    private suspend fun currentProtectedKeys(): Set<String> =
        protectedSet(settings.protectFavorites.first(), database.favoriteDao().observeAllRefs().first())

    private fun protectedSet(protect: Boolean, refs: List<FavoriteRef>): Set<String> =
        if (protect) refs.mapTo(HashSet()) { "${it.siteId}:${it.key}" } else emptySet()

    private companion object {
        const val MB = 1024L * 1024
    }
}
