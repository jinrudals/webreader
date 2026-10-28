package app.webreader.domain

import java.net.URI

data class Site(
    val id: String,
    val name: String,
    val baseUrl: String,
    val aliasHosts: Set<String>,
    val seriesPattern: String,
    val episodePattern: String,
) {
    val config: SiteConfig get() = SiteConfig(baseUrl, aliasHosts)

    fun classifier(): PageClassifier = PageClassifier(seriesPattern, episodePattern)

    fun hosts(): Set<String> {
        val base = runCatching { URI(baseUrl).host?.lowercase() }.getOrNull()
        return buildSet {
            if (!base.isNullOrEmpty()) add(base)
            aliasHosts.forEach { h -> h.trim().lowercase().takeIf { it.isNotEmpty() }?.let(::add) }
        }
    }
}
