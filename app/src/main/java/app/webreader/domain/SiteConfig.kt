package app.webreader.domain

import java.net.URI

data class SiteConfig(val baseUrl: String, val aliasHosts: Set<String>) {
    fun isKnownHost(host: String): Boolean {
        val h = host.lowercase()
        return h == URI(baseUrl).host?.lowercase() || aliasHosts.any { it.lowercase() == h }
    }
}
