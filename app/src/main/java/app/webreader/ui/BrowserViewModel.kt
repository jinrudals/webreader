package app.webreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.webreader.AppContainer
import app.webreader.data.db.Favorite
import app.webreader.data.db.FavoriteDao
import app.webreader.data.db.ReadHistory
import app.webreader.data.db.ReadHistoryDao
import app.webreader.domain.PageType
import app.webreader.domain.Site
import app.webreader.domain.SiteRegistry
import app.webreader.domain.UrlKeyer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class BrowserViewModel(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadHistoryDao,
    private val keyer: UrlKeyer,
    private val registry: () -> SiteRegistry,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private fun siteOf(url: String): Site? = registry().forUrl(url)

    private fun typeOf(site: Site, key: String): PageType = site.classifier().classify(key)

    fun pageType(url: String): PageType {
        val site = siteOf(url) ?: return PageType.OTHER
        return typeOf(site, keyer.key(url))
    }

    fun onPageFinished(url: String, title: String?): Job = viewModelScope.launch {
        val site = siteOf(url) ?: return@launch
        val key = keyer.key(url)
        if (typeOf(site, key) == PageType.OTHER) return@launch
        val position = historyDao.get(site.id, key)?.lastPosition ?: 0
        historyDao.upsert(ReadHistory(site.id, key, position, now()))
    }

    fun savePosition(url: String, y: Int): Job = viewModelScope.launch {
        val site = siteOf(url) ?: return@launch
        val key = keyer.key(url)
        if (typeOf(site, key) == PageType.OTHER) return@launch
        historyDao.upsert(ReadHistory(site.id, key, y, now()))
    }

    suspend fun restoreTarget(url: String): Int? {
        val site = siteOf(url) ?: return null
        return historyDao.get(site.id, keyer.key(url))?.lastPosition?.takeIf { it > 0 }
    }

    fun toggleFavorite(url: String, title: String): Job = viewModelScope.launch {
        val site = siteOf(url) ?: return@launch
        val key = keyer.key(url)
        val type = when (typeOf(site, key)) {
            PageType.OTHER -> return@launch
            PageType.SERIES -> Favorite.TYPE_SERIES
            PageType.EPISODE -> Favorite.TYPE_EPISODE
        }
        if (favoriteDao.observeIsFavorite(site.id, key).first()) {
            favoriteDao.delete(site.id, key)
        } else {
            favoriteDao.upsert(Favorite(site.id, key, type, title.ifBlank { key }, now()))
        }
    }

    fun isFavorite(url: String): Flow<Boolean> {
        val site = siteOf(url) ?: return flowOf(false)
        return favoriteDao.observeIsFavorite(site.id, keyer.key(url))
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowserViewModel(
                container.database.favoriteDao(),
                container.database.readHistoryDao(),
                container.keyer,
                container.registrySnapshot,
            ) as T
    }
}
