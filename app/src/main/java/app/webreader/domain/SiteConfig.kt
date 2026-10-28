package app.webreader.domain

import java.net.URI

data class SiteConfig(val baseUrl: String, val aliasHosts: Set<String>) {
    private val baseHost: String? by lazy {
        runCatching { URI(baseUrl).host?.lowercase() }.getOrNull()
    }

    fun isKnownHost(host: String): Boolean {
        val h = host.lowercase()
        return h == baseHost || aliasHosts.any { it.lowercase() == h }
    }
}
