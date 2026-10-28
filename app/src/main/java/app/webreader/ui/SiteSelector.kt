package app.webreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.webreader.domain.Site

@Composable
fun SiteSelector(sites: List<Site>, current: Site?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = sites.isNotEmpty()) {
            Text((current?.name ?: "No site") + " ▾", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sites.forEach { site ->
                DropdownMenuItem(
                    text = { Text(if (site.id == current?.id) "✓ ${site.name}" else site.name) },
                    onClick = {
                        expanded = false
                        onSelect(site.id)
                    },
                )
            }
        }
    }
}
