package app.webreader.ui

/** What the browser last acted on; [pendingSiteId] is the site an open request already navigated for. */
data class BrowserLoadState(
    val handledRequestId: Long? = null,
    val seenSiteId: String? = null,
    val pendingSiteId: String? = null,
)

data class BrowserLoadDecision(val url: String?, val state: BrowserLoadState)

/** Decides what the WebView should load; an open request wins over the site switch it triggers. */
fun decideLoad(
    state: BrowserLoadState,
    request: OpenRequest?,
    requestSiteId: String?,
    currentSiteId: String?,
    currentBaseUrl: String?,
): BrowserLoadDecision {
    if (request != null && request.id != state.handledRequestId) {
        val pending = requestSiteId?.takeIf { it != currentSiteId }
        return BrowserLoadDecision(
            request.url,
            BrowserLoadState(request.id, currentSiteId, pending),
        )
    }
    if (currentSiteId != state.seenSiteId) {
        val next = state.copy(seenSiteId = currentSiteId, pendingSiteId = null)
        val skip = currentSiteId == null || currentSiteId == state.pendingSiteId
        return BrowserLoadDecision(if (skip) null else currentBaseUrl, next)
    }
    return BrowserLoadDecision(null, state)
}
