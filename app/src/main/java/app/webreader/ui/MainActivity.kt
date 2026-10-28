package app.webreader.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import app.webreader.AppContainer
import app.webreader.WebReaderApp
import kotlinx.coroutines.launch

enum class Destination(val label: String) { Browser("Browser"), Library("Library"), Settings("Settings") }

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf(Destination.Browser)
    private var openRequest by mutableStateOf<OpenRequest?>(null)
    private var nextRequestId = 0L
    private lateinit var container: AppContainer

    /** Entry point for Library: select [siteId], then show [url] in the Browser tab. */
    fun openInBrowser(siteId: String, url: String) {
        container.appScope.launch { container.selectSite(siteId) }
        openRequest = OpenRequest(url, ++nextRequestId)
        destination = Destination.Browser
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as WebReaderApp).container
        setContent {
            MaterialTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Destination.entries.forEach { d ->
                                NavigationBarItem(
                                    selected = destination == d,
                                    onClick = { destination = d },
                                    icon = { Text(d.label.take(1)) },
                                    label = { Text(d.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val mod = Modifier.padding(padding)
                    // Browser stays composed so the WebView and its history survive tab switches.
                    val vm: BrowserViewModel = viewModel(factory = BrowserViewModel.Factory(container))
                    BrowserScreen(
                        request = openRequest,
                        viewModel = vm,
                        container = container,
                        onAddSite = { destination = Destination.Settings },
                        visible = destination == Destination.Browser,
                        modifier = mod,
                    )
                    when (destination) {
                        Destination.Library -> {
                            val lib: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(container))
                            val sites by container.registry.collectAsState()
                            val current by container.currentSite.collectAsState()
                            LibraryScreen(
                                lib, sites.sites, current,
                                onSelectSite = { id -> container.appScope.launch { container.selectSite(id) } },
                                onOpen = ::openInBrowser,
                                modifier = mod,
                            )
                        }
                        Destination.Settings -> {
                            val sv: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container))
                            SettingsScreen(sv, modifier = mod)
                        }
                        Destination.Browser -> Unit
                    }
                }
            }
        }
    }
}
