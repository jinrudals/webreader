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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(false) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var aliases by rememberSaveable { mutableStateOf("") }
    var series by rememberSaveable { mutableStateOf("") }
    var episode by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("") }
    var protect by rememberSaveable { mutableStateOf(true) }

    var siteError by rememberSaveable { mutableStateOf<String?>(null) }
    var patternError by rememberSaveable { mutableStateOf<String?>(null) }
    var cacheError by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    // Populate once; later flow emissions must not overwrite what the user is typing.
    LaunchedEffect(Unit) {
        if (!loaded) {
            val cfg = viewModel.siteConfig.first()
            baseUrl = cfg.baseUrl
            aliases = cfg.aliasHosts.sorted().joinToString(", ")
            series = viewModel.seriesPattern.first()
            episode = viewModel.episodePattern.first()
            limit = viewModel.cacheLimitMb.first().toString()
            protect = viewModel.protectFavorites.first()
            loaded = true
        }
    }

    val sizeBytes by viewModel.cacheSizeBytes.collectAsState(initial = 0L)

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Site")
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; siteError = null; saved = null },
            label = { Text("Base URL") },
            singleLine = true,
            isError = siteError != null,
            supportingText = siteError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = aliases,
            onValueChange = { aliases = it; saved = null },
            label = { Text("Alias hosts (comma separated)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            scope.launch {
                viewModel.saveBaseUrl(baseUrl).fold(
                    onSuccess = {
                        viewModel.saveAliasHosts(aliases)
                        siteError = null
                        saved = "Site saved"
                    },
                    onFailure = { siteError = it.message ?: "Invalid URL"; saved = null },
                )
            }
        }) { Text("Save site") }

        Text("Page patterns (regex)")
        OutlinedTextField(
            value = series,
            onValueChange = { series = it; patternError = null; saved = null },
            label = { Text("Series pattern") },
            singleLine = true,
            isError = patternError != null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = episode,
            onValueChange = { episode = it; patternError = null; saved = null },
            label = { Text("Episode pattern") },
            singleLine = true,
            isError = patternError != null,
            supportingText = patternError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            scope.launch {
                viewModel.savePatterns(series, episode).fold(
                    onSuccess = { patternError = null; saved = "Patterns saved" },
                    onFailure = { patternError = it.message ?: "Invalid regex"; saved = null },
                )
            }
        }) { Text("Save patterns") }

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
