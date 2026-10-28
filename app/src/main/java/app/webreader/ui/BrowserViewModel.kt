package app.webreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.webreader.AppContainer
import app.webreader.data.db.Favorite
import app.webreader.data.db.FavoriteDao
import app.webreader.data.db.ReadHistory
import app.webreader.data.db.ReadHistoryDao
import app.webreader.domain.PageClassifier
import app.webreader.domain.PageType
import app.webreader.domain.UrlKeyer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BrowserViewModel(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadHistoryDao,
    private val keyer: UrlKeyer,
    private val classifier: () -> PageClassifier,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    fun pageType(url: String): PageType = classifier().classify(keyer.key(url))

    fun onPageFinished(url: String, title: String?): Job = viewModelScope.launch {
        val key = keyer.key(url)
        if (classifier().classify(key) == PageType.OTHER) return@launch
        val position = historyDao.get(key)?.lastPosition ?: 0
        historyDao.upsert(ReadHistory(key, position, clock()))
    }

    fun savePosition(url: String, y: Int): Job = viewModelScope.launch {
        val key = keyer.key(url)
        if (classifier().classify(key) == PageType.OTHER) return@launch
        historyDao.upsert(ReadHistory(key, y, clock()))
    }

    suspend fun restoreTarget(url: String): Int? =
        historyDao.get(keyer.key(url))?.lastPosition?.takeIf { it > 0 }

    fun toggleFavorite(url: String, title: String): Job = viewModelScope.launch {
        val key = keyer.key(url)
        val type = when (classifier().classify(key)) {
            PageType.OTHER -> return@launch
            PageType.SERIES -> Favorite.TYPE_SERIES
            PageType.EPISODE -> Favorite.TYPE_EPISODE
        }
        if (favoriteDao.observeIsFavorite(key).first()) {
            favoriteDao.delete(key)
        } else {
            favoriteDao.upsert(Favorite(key, type, title.ifBlank { key }, clock()))
        }
    }

    fun isFavorite(url: String): Flow<Boolean> = favoriteDao.observeIsFavorite(keyer.key(url))

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowserViewModel(
                container.database.favoriteDao(),
                container.database.readHistoryDao(),
                container.keyer,
                container.classifier,
            ) as T
    }
}
