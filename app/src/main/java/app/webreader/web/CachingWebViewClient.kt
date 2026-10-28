package app.webreader.web

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.webreader.data.CachedResource
import app.webreader.data.ResourceCache
import app.webreader.domain.SiteConfig
import app.webreader.domain.UrlKeyer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

private const val TAG = "CachingWebViewClient"
private const val MAX_CACHEABLE_BYTES = 20L * 1024 * 1024
private const val DEFAULT_MIME = "application/octet-stream"
private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*")
private val SKIPPED_REQUEST_HEADERS = setOf(
    "host", "range", "accept-encoding", "cookie", "authorization", "proxy-authorization", "referer",
)

class CachingWebViewClient(
    private val keyer: UrlKeyer,
    private val cache: ResourceCache,
    private val http: OkHttpClient,
    private val config: () -> SiteConfig,
    private val maxCacheableBytes: Long = MAX_CACHEABLE_BYTES,
    private val onPageFinished: (url: String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
        try {
            intercept(request)
        } catch (e: Exception) {
            Log.w(TAG, "intercept failed", e)
            null
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
        if (!config().isKnownHost(host)) return null

        val key = keyer.key(uri.toString())
        cache.get(key)?.let { return serve(it) }

        val builder = Request.Builder().url(keyer.resolve(key)).get()
        headers.forEach { (k, v) ->
            if (k.lowercase() !in SKIPPED_REQUEST_HEADERS) builder.header(k, v)
        }
        val res = http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // A redirect that left the site must not be cached or served.
            if (!config().isKnownHost(resp.request.url.host)) return null
            val body = resp.body ?: return null
            val bytes = readBounded(body) ?: return null
            val type = body.contentType()
            CachedResource(
                mimeType = type?.let { "${it.type}/${it.subtype}".lowercase() } ?: DEFAULT_MIME,
                encoding = type?.charset()?.name(),
                bytes = bytes,
            )
        }
        // Cache headers are deliberately ignored: resources stay until evicted.
        try {
            cache.put(key, res)
        } catch (e: Exception) {
            Log.w(TAG, "cache put failed", e)
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
