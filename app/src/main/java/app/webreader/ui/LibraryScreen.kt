package app.webreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.webreader.data.db.Favorite

private enum class TypeFilter(val label: String, val type: String?) {
    All("All", null),
    Series("Series", Favorite.TYPE_SERIES),
    Episodes("Episodes", Favorite.TYPE_EPISODE),
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Favorites") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Recent") })
        }
        if (tab == 0) FavoritesTab(viewModel, onOpen) else RecentTab(viewModel, onOpen)
    }
}

@Composable
private fun FavoritesTab(viewModel: LibraryViewModel, onOpen: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(TypeFilter.All) }
    val flow = remember(filter, viewModel) {
        filter.type?.let { viewModel.favoritesOfType(it) } ?: viewModel.favorites
    }
    val items by flow.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        if (items.isEmpty()) {
            Text("No favorites yet", Modifier.padding(16.dp))
        } else {
            LazyColumn {
                items(items, key = { it.key }) { fav ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(viewModel.urlFor(fav.key)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(fav.title.ifBlank { fav.key }, style = MaterialTheme.typography.bodyLarge)
                            Text(fav.key, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { viewModel.removeFavorite(fav.key) }) { Text("Remove") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RecentTab(viewModel: LibraryViewModel, onOpen: (String) -> Unit) {
    val items by viewModel.recent.collectAsState(initial = emptyList())
    if (items.isEmpty()) {
        Text("Nothing read yet", Modifier.padding(16.dp))
        return
    }
    LazyColumn {
        items(items, key = { it.key }) { h ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(viewModel.urlFor(h.key)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(h.key, style = MaterialTheme.typography.bodyLarge)
                Text("Last position: ${h.lastPosition}", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}
