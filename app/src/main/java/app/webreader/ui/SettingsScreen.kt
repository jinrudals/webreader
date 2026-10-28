package app.webreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import java.net.URI
import kotlinx.coroutines.launch

/** Restored selections must survive until the first real site-list emission. */
internal fun shouldClearSelection(id: String, loadedIds: List<String>?): Boolean =
    loadedIds != null && id != NEW_SITE && id !in loadedIds

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf("") }
    var protect by rememberSaveable { mutableStateOf(true) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val loadedSites by viewModel.sites.collectAsState(initial = null)
    val sites = loadedSites.orEmpty()

    var cacheError by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    // Populate once; later flow emissions must not overwrite what the user is typing.
    LaunchedEffect(Unit) {
        if (!loaded) {
            limit = viewModel.cacheLimitMb.first().toString()
            protect = viewModel.protectFavorites.first()
            loaded = true
        }
    }

    val editTarget = editing
    if (editTarget != null) {
        val site = sites.firstOrNull { it.id == editTarget }
        if (editTarget == NEW_SITE || site != null) {
            SiteEditForm(
                site = site,
                onSave = { n, u, a, sp, ep ->
                    if (site == null) viewModel.addSite(n, u, a, sp, ep).map { }
                    else viewModel.updateSite(site.id, n, u, a, sp, ep)
                },
                onDone = { editing = null },
                modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            )
            return
        }
        if (shouldClearSelection(editTarget, loadedSites?.map { it.id })) editing = null
    }

    val sizeBytes by viewModel.cacheSizeBytes.collectAsState(initial = 0L)

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sites")
        sites.forEach { site ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(site.name)
                    Text(runCatching { URI(site.baseUrl).host }.getOrNull() ?: site.baseUrl)
                }
                TextButton(onClick = { editing = site.id }) { Text("Edit") }
                TextButton(onClick = { deleting = site.id }) { Text("Delete") }
            }
        }
        if (sites.isEmpty()) Text("No sites yet")
        Button(onClick = { editing = NEW_SITE }) { Text("Add site") }

        Text("Cache")
        Text("Used: %.1f MB".format(sizeBytes / (1024.0 * 1024.0)))
        OutlinedTextField(
            value = limit,
            onValueChange = { limit = it.filter(Char::isDigit); cacheError = null; saved = null },
            label = { Text("Cache limit (MB)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = cacheError != null,
            supportingText = cacheError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = protect, onCheckedChange = { protect = it; saved = null })
            Text("Protect favorites from eviction")
        }
        Button(onClick = {
            val mb = limit.toIntOrNull()
            if (mb == null || mb < 1) {
                cacheError = "Enter a limit of at least 1 MB"
                saved = null
            } else {
                scope.launch {
                    viewModel.saveCache(mb, protect)
                    cacheError = null
                    saved = "Cache settings saved"
                }
            }
        }) { Text("Save cache settings") }
        OutlinedButton(onClick = { confirmClear = true }) { Text("Clear cache (all entries)") }

        saved?.let { Text(it) }
    }

    deleting?.let { id ->
        val site = sites.firstOrNull { it.id == id }
        if (site == null) {
            if (shouldClearSelection(id, loadedSites?.map { it.id })) deleting = null
        } else {
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete ${site.name}?") },
                text = { Text("Favorites, history and cache of this site are removed.") },
                confirmButton = {
                    TextButton(onClick = { scope.launch { viewModel.deleteSite(id) }; deleting = null }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cache?") },
            text = { Text("All cached pages and resources are removed, including those of favorites.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearCache(); confirmClear = false; saved = "Cache cleared" }) {
                    Text("Clear")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

internal const val NEW_SITE = "\u0000new"
