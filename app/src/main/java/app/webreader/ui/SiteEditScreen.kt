package app.webreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.webreader.domain.Site
import kotlinx.coroutines.launch

/** Add/edit form; fields initialize once from [site] (null = new) and survive rotation. */
@Composable
fun SiteEditForm(
    site: Site?,
    onSave: suspend (name: String, baseUrl: String, aliases: String, series: String, episode: String) -> Result<Unit>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(site?.id) { mutableStateOf(site?.name ?: "") }
    var baseUrl by rememberSaveable(site?.id) { mutableStateOf(site?.baseUrl ?: "") }
    var aliases by rememberSaveable(site?.id) { mutableStateOf(site?.aliasHosts?.sorted()?.joinToString(", ") ?: "") }
    var series by rememberSaveable(site?.id) { mutableStateOf(site?.seriesPattern ?: "") }
    var episode by rememberSaveable(site?.id) { mutableStateOf(site?.episodePattern ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (site == null) "Add site" else "Edit site")
        OutlinedTextField(
            value = name, onValueChange = { name = it; error = null },
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it; error = null },
            label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = aliases, onValueChange = { aliases = it; error = null },
            label = { Text("Alias hosts (comma separated)") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = series, onValueChange = { series = it; error = null },
            label = { Text("Series pattern (regex)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = episode, onValueChange = { episode = it; error = null },
            label = { Text("Episode pattern (regex)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    onSave(name, baseUrl, aliases, series.trim(), episode.trim()).fold(
                        onSuccess = { onDone() },
                        onFailure = { error = it.message ?: "Invalid input" },
                    )
                }
            }) { Text("Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
    }
}
