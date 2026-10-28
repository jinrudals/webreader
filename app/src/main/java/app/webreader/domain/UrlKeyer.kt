package app.webreader.domain

class UrlKeyer(private val config: () -> SiteConfig) {

    fun key(url: String): String {
        val noFragment = url.substringBefore('#')
        val rawQuery = if ('?' in noFragment) noFragment.substringAfter('?') else null
        var rest = noFragment.substringBefore('?')
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) {
            val afterScheme = rest.substring(schemeEnd + 3)
            val slash = afterScheme.indexOf('/')
            rest = if (slash < 0) "" else afterScheme.substring(slash)
        }
        val path = rest.trimEnd('/').ifEmpty { "/" }
        val params = rawQuery
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

    fun resolve(key: String): String = config().baseUrl.trimEnd('/') + (if (key.startsWith("/")) key else "/$key")
}
