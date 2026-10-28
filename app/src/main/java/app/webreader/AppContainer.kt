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

    @Volatile private var siteConfigSnapshot: SiteConfig = runBlocking { settings.siteConfig.first() }
    @Volatile private var cacheLimitBytes: Long = 0L
    @Volatile private var protectedKeys: Set<String> = emptySet()
    @Volatile private var classifierSnapshot: PageClassifier = PageClassifier("", "")

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
        settings.siteConfig.onEach { siteConfigSnapshot = it }.launchIn(appScope)
        settings.cacheLimitMb.onEach { cacheLimitBytes = (it.toLong() * MB).coerceAtLeast(0) }.launchIn(appScope)
        combine(settings.seriesPattern, settings.episodePattern) { s, e -> PageClassifier(s, e) }
            .onEach { classifierSnapshot = it }.launchIn(appScope)
        combine(settings.protectFavorites, database.favoriteDao().observeAll()) { protect, favs ->
            if (protect) favs.mapTo(HashSet()) { it.key } else emptySet()
        }.onEach { protectedKeys = it }.launchIn(appScope)
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
