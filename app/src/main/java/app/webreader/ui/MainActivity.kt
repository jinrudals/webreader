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
import app.webreader.WebReaderApp

enum class Destination(val label: String) { Browser("Browser"), Library("Library"), Settings("Settings") }

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf(Destination.Browser)
    private var browserUrl by mutableStateOf<String?>(null)

    /** Entry point for Library (Task 8): show [url] in the Browser tab. */
    fun openInBrowser(url: String) {
        browserUrl = url
        destination = Destination.Browser
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WebReaderApp).container
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
                        url = browserUrl,
                        viewModel = vm,
                        container = container,
                        visible = destination == Destination.Browser,
                        modifier = mod,
                    )
                    when (destination) {
                        Destination.Library -> Text("Library", mod)
                        Destination.Settings -> Text("Settings", mod)
                        Destination.Browser -> Unit
                    }
                }
            }
        }
    }
}
