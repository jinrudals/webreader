package app.webreader.domain

import java.net.URI

class UrlKeyer(private val config: () -> SiteConfig) {

    fun key(url: String): String {
        val uri = URI(url)
        val rawPath = uri.rawPath.orEmpty()
        val path = rawPath.trimEnd('/').ifEmpty { "/" }
        val params = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.map { p ->
                val i = p.indexOf('=')
                if (i < 0) p to "" else p.substring(0, i) to p.substring(i + 1)
            }
            ?.sortedWith(compareBy({ it.first }, { it.second }))
            .orEmpty()
        if (params.isEmpty()) return path
        return path + "?" + params.joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
    }

    fun resolve(key: String): String = config().baseUrl.trimEnd('/') + key
}
