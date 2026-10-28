package app.webreader.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    url: String?,
    viewModel: BrowserViewModel,
    container: AppContainer,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseUrl = container.siteConfig().baseUrl
    if (baseUrl.isBlank()) {
        if (visible) Box(modifier.fillMaxSize(), Alignment.Center) { Text("Set the site URL in Settings") }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentUrl by remember { mutableStateOf(url ?: baseUrl) }
    var title by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    val webView = remember { arrayOfNulls<WebView>(1) }
    val density = context.resources.displayMetrics.density

    fun saveCurrent() {
        val wv = webView[0] ?: return
        val u = wv.url ?: return
        viewModel.savePosition(u, wv.scrollY)
    }

    // Last scroll seen for a page, flushed once the WebView moves on to a different URL.
    var lastScroll by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val client = remember {
        CachingWebViewClient(container.keyer, container.resourceCache, container.http, container.siteConfig) { finished ->
            val wv = webView[0]
            lastScroll?.let { (u, y) -> if (u != finished) viewModel.savePosition(u, y) }
            lastScroll = null
            currentUrl = finished
            title = wv?.title.orEmpty()
            canGoBack = wv?.canGoBack() == true
            viewModel.onPageFinished(finished, wv?.title)
            scope.launch {
                val y = viewModel.restoreTarget(finished) ?: return@launch
                wv?.evaluateJavascript("window.scrollTo(0,${y / density})", null)
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) saveCurrent() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            webView[0]?.destroy()
            webView[0] = null
        }
    }

    BackHandler(enabled = visible && canGoBack) { webView[0]?.goBack() }

    val favorite by remember(currentUrl) { viewModel.isFavorite(currentUrl) }.collectAsState(false)
    val favoritable = viewModel.pageType(currentUrl) != PageType.OTHER

    Column(if (visible) modifier.fillMaxSize() else Modifier.size(0.dp)) {
        if (visible) {
            TopAppBar(
                title = { Text(title.ifBlank { "WebReader" }, maxLines = 1) },
                actions = {
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
                    setOnScrollChangeListener { v, _, y, _, _ -> (v as WebView).url?.let { lastScroll = it to y } }
                    webView[0] = this
                    loadUrl(currentUrl)
                }
            },
            update = { wv ->
                if (url != null && url != wv.tag) {
                    saveCurrent()
                    wv.tag = url
                    wv.loadUrl(url)
                }
            },
        )
    }
}
