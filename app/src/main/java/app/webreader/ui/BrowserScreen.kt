package app.webreader.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.webreader.AppContainer
import app.webreader.domain.PageType
import app.webreader.web.CachingWebViewClient
import kotlinx.coroutines.launch

/** Each Library tap creates a new request so reopening the same URL still navigates. */
data class OpenRequest(val url: String, val id: Long)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    request: OpenRequest?,
    viewModel: BrowserViewModel,
    container: AppContainer,
    onAddSite: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val sites by container.registry.collectAsState()
    val site by container.currentSite.collectAsState()
    val current = site
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentUrl by remember { mutableStateOf(request?.url ?: current?.baseUrl.orEmpty()) }
    var loadState by remember {
        mutableStateOf(
            BrowserLoadState(
                handledRequestId = request?.id,
                seenSiteId = current?.id,
                pendingSiteId = request?.let { sites.forUrl(it.url)?.id }?.takeIf { it != current?.id },
            ),
        )
    }
    var title by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    val webView = remember { arrayOfNulls<WebView>(1) }
    val density = context.resources.displayMetrics.density

    val tracker = remember { ScrollTracker { u, y -> viewModel.savePosition(u, y) } }

    val client = remember {
        CachingWebViewClient(
            container.keyer, container.registrySnapshot, container.resourceCache, container.http,
            onPageStarted = { started -> tracker.onPageStarted(started) },
        ) { finished ->
            val wv = webView[0]
            currentUrl = finished
            title = wv?.title.orEmpty()
            canGoBack = wv?.canGoBack() == true
            viewModel.onPageFinished(finished, wv?.title)
            scope.launch {
                val y = viewModel.restoreTarget(finished)
                tracker.onPageFinished(finished, y ?: 0)
                if (y != null) wv?.evaluateJavascript("window.scrollTo(0,${y / density})", null)
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) tracker.onPause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            tracker.onPause()
            webView[0]?.destroy()
            webView[0] = null
        }
    }

    BackHandler(enabled = visible && canGoBack) { webView[0]?.goBack() }

    val favorite by remember(currentUrl) { viewModel.isFavorite(currentUrl) }.collectAsState(false)
    val favoritable = viewModel.pageType(currentUrl) != PageType.OTHER

    LaunchedEffect(current == null) {
        if (current == null) {
            currentUrl = ""
            title = ""
            canGoBack = false
            loadState = loadState.copy(seenSiteId = null, pendingSiteId = null)
        }
    }

    if (current == null) {
        if (visible) {
            Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Add a site to start reading")
                Button(onClick = onAddSite) { Text("Add a site") }
            }
        }
        return
    }

    Column(if (visible) modifier.fillMaxSize() else Modifier.size(0.dp)) {
        if (visible) {
            TopAppBar(
                title = { Text(title.ifBlank { "WebReader" }, maxLines = 1) },
                actions = {
                    SiteSelector(sites.sites, current) { id -> scope.launch { container.selectSite(id) } }
                    IconButton(onClick = { viewModel.toggleFavorite(currentUrl, title) }, enabled = favoritable) {
                        Text(if (favorite) "★" else "☆")
                    }
                },
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = client
                    setOnScrollChangeListener { _, _, y, _, _ -> tracker.onScroll(y) }
                    webView[0] = this
                    if (currentUrl.isNotBlank()) loadUrl(currentUrl)
                }
            },
            update = { wv ->
                val decision = decideLoad(
                    loadState, request, request?.let { sites.forUrl(it.url)?.id }, current.id, current.baseUrl,
                )
                if (decision.state != loadState) loadState = decision.state
                decision.url?.let { wv.loadUrl(it) }
            },
            onRelease = { wv ->
                tracker.onPause()
                wv.destroy()
                webView[0] = null
            },
        )
    }
}
