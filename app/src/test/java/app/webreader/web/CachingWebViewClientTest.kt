package app.webreader.web

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.webreader.data.CachedResource
import app.webreader.data.ResourceCache
import app.webreader.domain.Site
import app.webreader.domain.SiteRegistry
import app.webreader.domain.UrlKeyer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeRequest(
    private val url: String,
    private val method: String = "GET",
    private val headers: Map<String, String> = emptyMap(),
    private val mainFrame: Boolean = false,
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)
    override fun isForMainFrame() = mainFrame
    override fun isRedirect() = false
    override fun hasGesture() = false
    override fun getMethod() = method
    override fun getRequestHeaders() = headers
}

@RunWith(RobolectricTestRunner::class)
class CachingWebViewClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: ResourceCache
    private var registry = SiteRegistry(emptyList())
    private var finished: String? = null
    private lateinit var client: CachingWebViewClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        registry = SiteRegistry(listOf(siteA("http://127.0.0.1:${server.port}", setOf("old.example"))))
        cache = ResourceCache(tmp.newFolder(), { 10_000_000L }, { emptySet() })
        client = CachingWebViewClient(UrlKeyer(), { registry }, cache, OkHttpClient()) { finished = it }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun siteA(base: String, alias: Set<String>) = Site("a", "A", base, alias, "", "")

    private fun intercept(req: WebResourceRequest): WebResourceResponse? = client.shouldInterceptRequest(null, req)

    private fun base() = "http://127.0.0.1:${server.port}"

    @Test
    fun hitServedWithoutNetwork() {
        cache.put("a:"+"/a.png", CachedResource("image/png", null, byteArrayOf(1, 2, 3)))
        server.shutdown()
        val r = intercept(FakeRequest("${base()}/a.png"))!!
        assertEquals("image/png", r.mimeType)
        assertEquals(200, r.statusCode)
        assertEquals("*", r.responseHeaders["Access-Control-Allow-Origin"])
        assertEquals(listOf<Byte>(1, 2, 3), r.data.readBytes().toList())
    }

    @Test
    fun missFetchesFromCurrentBaseAndStores() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("hi"))
        val r = intercept(FakeRequest("${base()}/p?b=2&a=1", headers = mapOf("Range-X" to "1", "Accept-Encoding" to "br")))!!
        assertEquals("text/html", r.mimeType)
        assertEquals("UTF-8", r.encoding)
        assertEquals("hi", String(r.data.readBytes()))
        val stored = cache.get("a:"+"/p?a=1&b=2")!!
        assertEquals("text/html", stored.mimeType)
        assertEquals("UTF-8", stored.encoding)
        assertEquals("hi", String(stored.bytes))
        assertNotNull(server.takeRequest().path)
    }

    @Test
    fun aliasHostRequestFetchesFromCurrentBase() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(byteArrayOf(9))))
        val r = intercept(FakeRequest("http://old.example/img/x.png"))!!
        assertEquals("image/png", r.mimeType)
        assertEquals("/img/x.png", server.takeRequest().path)
        assertNotNull(cache.get("a:"+"/img/x.png"))
    }

    @Test
    fun domainChangeStillHits() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("img"))
        assertNotNull(intercept(FakeRequest("${base()}/i.png")))
        server.shutdown()
        registry = SiteRegistry(listOf(siteA("http://new.example", setOf("127.0.0.1"))))
        val r = intercept(FakeRequest("http://new.example/i.png"))!!
        assertEquals("img", String(r.data.readBytes()))
    }

    @Test
    fun nonGetAndRangePassThrough() {
        assertNull(intercept(FakeRequest("${base()}/a", method = "POST")))
        assertNull(intercept(FakeRequest("${base()}/a", headers = mapOf("range" to "bytes=0-1"))))
        assertNull(intercept(FakeRequest("ftp://127.0.0.1/a")))
        assertNull(intercept(FakeRequest("http://other.example/a")))
        assertEquals(0, server.requestCount)
        assertNull(cache.get("/a"))
    }

    @Test
    fun non2xxNotCached() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        assertNull(intercept(FakeRequest("${base()}/missing")))
        assertNull(cache.get("a:"+"/missing"))
    }

    @Test
    fun networkFailureWithoutCacheReturnsNull() {
        server.shutdown()
        assertNull(intercept(FakeRequest("${base()}/x")))
    }

    @Test
    fun sensitiveHeadersNotForwarded() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("ok"))
        val h = mapOf(
            "Cookie" to "s=1", "Authorization" to "Bearer x", "Referer" to "http://old.example/",
            "Proxy-Authorization" to "p", "X-Test" to "yes", "Range-X" to "1",
        )
        assertNotNull(intercept(FakeRequest("${base()}/h", headers = h)))
        val rec = server.takeRequest()
        assertNull(rec.getHeader("Cookie"))
        assertNull(rec.getHeader("Authorization"))
        assertNull(rec.getHeader("Referer"))
        assertNull(rec.getHeader("Proxy-Authorization"))
        assertEquals("yes", rec.getHeader("X-Test"))
        assertNull(rec.getHeader("Range"))
    }

    @Test
    fun redirectToUnknownHostNotCached() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${server.port}/other"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("evil"))
        assertNull(intercept(FakeRequest("${base()}/r")))
        assertNull(cache.get("a:"+"/r"))
        assertNull(cache.get("a:"+"/other"))
    }

    @Test
    fun oversizedBodyNotIntercepted() {
        client = CachingWebViewClient(UrlKeyer(), { registry }, cache, OkHttpClient(), maxCacheableBytes = 4L) { finished = it }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("0123456789"))
        assertNull(intercept(FakeRequest("${base()}/big")))
        assertNull(cache.get("a:"+"/big"))
    }

    @Test
    fun oversizedChunkedBodyNotIntercepted() {
        client = CachingWebViewClient(UrlKeyer(), { registry }, cache, OkHttpClient(), maxCacheableBytes = 4L) { finished = it }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setChunkedBody("0123456789", 3))
        assertNull(intercept(FakeRequest("${base()}/bigc")))
        assertNull(cache.get("a:"+"/bigc"))
    }

    @Test
    fun missingContentTypeDefaultsToOctetStream() {
        server.enqueue(MockResponse().setBody("x"))
        val r = intercept(FakeRequest("${base()}/n"))!!
        assertEquals("application/octet-stream", r.mimeType)
    }

    @Test
    fun charsetParsedCaseInsensitively() {
        server.enqueue(MockResponse().setHeader("Content-Type", "Text/HTML; CHARSET=UTF-8").setBody("x"))
        val r = intercept(FakeRequest("${base()}/c"))!!
        assertEquals("text/html", r.mimeType)
        assertEquals("UTF-8", r.encoding?.uppercase())
    }

    @Test
    fun putFailureStillServes() {
        val zero = ResourceCache(tmp.newFolder(), { 0L }, { emptySet() })
        client = CachingWebViewClient(UrlKeyer(), { registry }, zero, OkHttpClient()) { finished = it }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("ok"))
        assertEquals("ok", String(intercept(FakeRequest("${base()}/z"))!!.data.readBytes()))
    }

    @Test
    fun onPageStartedForwards() {
        var started: String? = null
        val c = CachingWebViewClient(UrlKeyer(), { registry }, cache, OkHttpClient(), onPageStarted = { started = it }) {}
        c.onPageStarted(null, "http://x/z", null)
        assertEquals("http://x/z", started)
        c.onPageStarted(null, null, null)
    }

    @Test
    fun onPageFinishedForwards() {
        client.onPageFinished(null, "http://x/y")
        assertTrue(finished == "http://x/y")
    }

    @Test
    fun mainFrameIsNetworkFirstAndRefreshesCache() {
        cache.put("a:"+"/p", CachedResource("text/html", "utf-8", "old".toByteArray()))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("new"))
        assertEquals("new", String(intercept(FakeRequest("${base()}/p", mainFrame = true))!!.data.readBytes()))
        assertEquals("new", String(cache.get("a:"+"/p")!!.bytes))
    }

    @Test
    fun cachedHtmlIsNetworkFirstEvenForSubFrame() {
        cache.put("a:"+"/p", CachedResource("text/html", "utf-8", "old".toByteArray()))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("new"))
        assertEquals("new", String(intercept(FakeRequest("${base()}/p"))!!.data.readBytes()))
    }

    @Test
    fun htmlFallsBackToCacheOnFailure() {
        cache.put("a:"+"/p", CachedResource("text/html", "utf-8", "old".toByteArray()))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("old", String(intercept(FakeRequest("${base()}/p", mainFrame = true))!!.data.readBytes()))
        server.shutdown()
        assertEquals("old", String(intercept(FakeRequest("${base()}/p", mainFrame = true))!!.data.readBytes()))
    }

    @Test
    fun cookiesForwardedAndSetCookieWrittenBackNotCached() {
        val set = mutableListOf<Pair<String, String>>()
        client = CachingWebViewClient(
            UrlKeyer(), { registry }, cache, OkHttpClient(),
            cookies = { "sid=abc" }, setCookie = { u, v -> set += u to v },
        ) { finished = it }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").addHeader("Set-Cookie", "a=1; Path=/").setBody("x"),
        )
        assertNotNull(intercept(FakeRequest("${base()}/c", mainFrame = true)))
        assertEquals("sid=abc", server.takeRequest().getHeader("Cookie"))
        assertEquals(1, set.size)
        assertEquals("a=1; Path=/", set[0].second)
        assertNull(cache.get("a:"+"/c"))
    }

    @Test
    fun sameKeyDifferentSitesIsolatedInCache() {
        val b = Site("b", "B", "http://localhost:${server.port}", emptySet(), "", "")
        registry = SiteRegistry(registry.sites + b)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("from-a"))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("from-b"))
        assertEquals("from-a", String(intercept(FakeRequest("${base()}/i.png"))!!.data.readBytes()))
        assertEquals("from-b", String(intercept(FakeRequest("http://localhost:${server.port}/i.png"))!!.data.readBytes()))
        server.shutdown()
        assertEquals("from-a", String(intercept(FakeRequest("${base()}/i.png"))!!.data.readBytes()))
        assertEquals("from-b", String(intercept(FakeRequest("http://localhost:${server.port}/i.png"))!!.data.readBytes()))
        assertNotNull(cache.get("a:/i.png"))
        assertNotNull(cache.get("b:/i.png"))
    }

    @Test
    fun aliasHostServedFromThatSitesBase() {
        val b = Site("b", "B", "http://localhost:${server.port}", setOf("b.alias"), "", "")
        registry = SiteRegistry(registry.sites + b)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("x"))
        assertNotNull(intercept(FakeRequest("http://b.alias/y.png")))
        assertEquals("localhost:${server.port}", server.takeRequest().getHeader("Host"))
        assertNotNull(cache.get("b:/y.png"))
        assertNull(cache.get("a:/y.png"))
    }

    @Test
    fun redirectToOtherSitesHostNotServedNotCachedNoCookie() {
        val b = Site("b", "B", "http://localhost:${server.port}", emptySet(), "", "")
        registry = SiteRegistry(registry.sites + b)
        val asked = mutableListOf<String>()
        client = CachingWebViewClient(
            UrlKeyer(), { registry }, cache, OkHttpClient(),
            cookies = { asked += it; "sid=abc" },
        ) { finished = it }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${server.port}/other"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("evil"))
        assertNull(intercept(FakeRequest("${base()}/r")))
        assertNull(cache.get("a:/r"))
        assertNull(cache.get("a:/other"))
        assertNull(cache.get("b:/other"))
        assertEquals(1, server.requestCount)
        assertTrue(asked.none { "localhost" in it })
        assertEquals(1, asked.size)
    }

    @Test
    fun redirectWithinSiteKeepsCookieOnlyForSiteHosts() {
        registry = SiteRegistry(listOf(siteA(base(), setOf("localhost"))))
        val asked = mutableListOf<String>()
        client = CachingWebViewClient(
            UrlKeyer(), { registry }, cache, OkHttpClient(),
            cookies = { asked += it; "sid=abc" },
        ) { finished = it }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${server.port}/final"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("ok"))
        assertEquals("ok", String(intercept(FakeRequest("${base()}/r"))!!.data.readBytes()))
        assertEquals("sid=abc", server.takeRequest().getHeader("Cookie"))
        assertEquals("sid=abc", server.takeRequest().getHeader("Cookie"))
        assertEquals(listOf("${base()}/r", "http://localhost:${server.port}/final"), asked)
        assertNotNull(cache.get("a:/r"))
    }

    @Test
    fun tooManyRedirectsReturnsNull() {
        repeat(7) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "${base()}/n$it")) }
        assertNull(intercept(FakeRequest("${base()}/r")))
        assertEquals(6, server.requestCount)
        assertNull(cache.get("a:/r"))
    }
}
