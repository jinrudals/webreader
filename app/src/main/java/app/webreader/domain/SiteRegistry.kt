package app.webreader.domain

import java.net.URI

class SiteRegistry(val sites: List<Site>) {
    private val byHost: Map<String, Site> = buildMap {
        sites.forEach { site -> site.hosts().forEach { putIfAbsent(it, site) } }
    }

    fun forHost(host: String): Site? = byHost[host.lowercase()]

    fun forUrl(url: String): Site? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        return forHost(host)
    }

    fun byId(id: String): Site? = sites.firstOrNull { it.id == id }

    fun conflictingHost(candidate: Site): String? =
        candidate.hosts().firstOrNull { byHost[it]?.let { owner -> owner.id != candidate.id } == true }

    companion object {
        fun resolveCurrent(sites: List<Site>, savedId: String?): Site? =
            sites.firstOrNull { it.id == savedId } ?: sites.firstOrNull()
    }
}
