package app.webreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.webreader.AppContainer
import app.webreader.data.db.Favorite
import app.webreader.data.db.FavoriteDao
import app.webreader.data.db.ReadHistory
import app.webreader.data.db.ReadHistoryDao
import app.webreader.domain.SiteRegistry
import app.webreader.domain.UrlKeyer
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadHistoryDao,
    private val keyer: UrlKeyer,
    private val registry: () -> SiteRegistry,
    private val currentSiteId: StateFlow<String>,
) : ViewModel() {

    val favorites: Flow<List<Favorite>> = currentSiteId.flatMapLatest { if (it.isBlank()) flowOf(emptyList()) else favoriteDao.observeAll(it) }

    val recent: Flow<List<ReadHistory>> = currentSiteId.flatMapLatest {
        if (it.isBlank()) flowOf(emptyList()) else historyDao.observeRecent(it, RECENT_LIMIT)
    }

    fun favoritesOfType(type: String): Flow<List<Favorite>> =
        favorites.map { list -> list.filter { it.type == type } }

    fun urlFor(key: String): String = keyer.resolve(registry().byId(currentSiteId.value)?.baseUrl.orEmpty(), key)

    fun removeFavorite(key: String): Job = viewModelScope.launch { favoriteDao.delete(currentSiteId.value, key) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(
                container.database.favoriteDao(),
                container.database.readHistoryDao(),
                container.keyer,
                container.registrySnapshot,
                container.currentSiteIdFlow,
            ) as T
    }

    private companion object {
        const val RECENT_LIMIT = 50
    }
}
