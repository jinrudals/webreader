package app.webreader.domain

enum class PageType { SERIES, EPISODE, OTHER }

class PageClassifier(seriesPattern: String, episodePattern: String) {
    private val series: Regex? = compile(seriesPattern)
    private val episode: Regex? = compile(episodePattern)

    fun classify(key: String): PageType = when {
        episode?.containsMatchIn(key) == true -> PageType.EPISODE
        series?.containsMatchIn(key) == true -> PageType.SERIES
        else -> PageType.OTHER
    }

    private fun compile(pattern: String): Regex? =
        if (pattern.isEmpty()) null else runCatching { Regex(pattern) }.getOrNull()
}
