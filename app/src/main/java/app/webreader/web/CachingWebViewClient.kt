package app.webreader.web

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.webreader.data.CachedResource
import app.webreader.data.ResourceCache
import app.webreader.domain.Site
import app.webreader.domain.SiteRegistry
import app.webreader.domain.UrlKeyer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException

private const val TAG = "CachingWebViewClient"
private const val MAX_CACHEABLE_BYTES = 20L * 1024 * 1024
private const val MAX_REDIRECTS = 5
private const val DEFAULT_MIME = "application/octet-stream"
private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*")
private val SKIPPED_REQUEST_HEADERS = setOf(
    "host", "range", "accept-encoding", "cookie", "authorization", "proxy-authorization", "referer",
)

class CachingWebViewClient(
    private val keyer: UrlKeyer,
    private val registry: () -> SiteRegistry,
    private val cache: ResourceCache,
    private val http: OkHttpClient,
    private val maxCacheableBytes: Long = MAX_CACHEABLE_BYTES,
    private val onPageStarted: (url: String) -> Unit = {},
    private val cookies: (String) -> String? = { android.webkit.CookieManager.getInstance().getCookie(it) },
    private val setCookie: (String, String) -> Unit = { url, value ->
        android.webkit.CookieManager.getInstance().setCookie(url, value)
    },
    private val onPageFinished: (url: String) -> Unit,
) : WebViewClient() {

    private val noRedirectHttp: OkHttpClient by lazy { http.newBuilder().followRedirects(false).build() }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
        try {
            intercept(request)
        } catch (e: Exception) {
            Log.w(TAG, "intercept failed", e)
            null
        }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url != null) onPageStarted.invoke(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url != null) onPageFinished.invoke(url)
    }

    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val headers = request.requestHeaders.orEmpty()
        if (headers.keys.any { it.equals("Range", ignoreCase = true) }) return null
        val uri = request.url
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host ?: return null
        val site = registry().forHost(host) ?: return null

        val key = keyer.key(uri.toString())
        val cacheKey = "${site.id}:$key"
        val cached = cache.get(cacheKey)
        val networkFirst = request.isForMainFrame || cached?.mimeType == "text/html"
        if (cached != null && !networkFirst) return serve(cached)

        val fetched = try {
            fetch(site, key, cacheKey, headers)
        } catch (e: IOException) {
            null
        }
        return when {
            fetched == null -> cached?.let { serve(it) }
            else -> fetched
        }
    }

    // Returns null when the response is unusable (non-2xx, off-site redirect, too many hops, too large).
    private fun fetch(site: Site, key: String, cacheKey: String, headers: Map<String, String>): WebResourceResponse? {
        val hosts = site.hosts()
        var url = keyer.resolve(site.baseUrl, key).toHttpUrlOrNull() ?: return null
        var setsCookie = false
        var hops = 0
        var fetchedRes: CachedResource? = null
        while (fetchedRes == null) {
            if (url.host.lowercase() !in hosts) return null
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) ->
                if (k.lowercase() !in SKIPPED_REQUEST_HEADERS) builder.header(k, v)
            }
            cookies(url.toString())?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            val outcome: Any? = noRedirectHttp.newCall(builder.build()).execute().use { resp ->
                val setCookies = resp.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    setsCookie = true
                    val target = resp.request.url.toString()
                    setCookies.forEach { runCatching { setCookie(target, it) } }
                }
                if (resp.isRedirect) {
                    val next = resp.header("Location")?.let { resp.request.url.resolve(it) } ?: return null
                    if (++hops > MAX_REDIRECTS) return null
                    next
                } else {
                    if (!resp.isSuccessful) return null
                    val body = resp.body ?: return null
                    val bytes = readBounded(body) ?: return null
                    val type = body.contentType()
                    CachedResource(
                        mimeType = type?.let { "${it.type}/${it.subtype}".lowercase() } ?: DEFAULT_MIME,
                        encoding = type?.charset()?.name(),
                        bytes = bytes,
                    )
                }
            }
            if (outcome is CachedResource) fetchedRes = outcome else url = outcome as HttpUrl
        }
        val res: CachedResource = fetchedRes
        if (!setsCookie) {
            try {
                cache.put(cacheKey, res)
            } catch (e: Exception) {
                Log.w(TAG, "cache put failed", e)
            }
        }
        return serve(res)
    }

    private fun readBounded(body: ResponseBody): ByteArray? {
        val len = body.contentLength()
        if (len > maxCacheableBytes) return null
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > maxCacheableBytes) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun serve(res: CachedResource) = WebResourceResponse(
        res.mimeType, res.encoding, 200, "OK", CORS_HEADERS, ByteArrayInputStream(res.bytes),
    )
}
