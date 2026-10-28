package app.webreader.ui

/** Tracks the settled page's scroll offset so saves never use a mid-navigation (reset to 0) position. */
class ScrollTracker(private val save: (url: String, y: Int) -> Unit) {
    private var current: String? = null
    private var lastY = 0
    private var settled = false

    fun onPageStarted(newUrl: String) {
        flush()
        settled = false
        current = null
    }

    fun onPageFinished(url: String, restoredY: Int) {
        current = url
        lastY = restoredY
        settled = true
    }

    fun onScroll(y: Int) {
        if (settled) lastY = y
    }

    fun onPause() {
        if (settled) flush()
    }

    private fun flush() {
        val url = current
        if (settled && !url.isNullOrBlank()) save(url, lastY)
    }
}
