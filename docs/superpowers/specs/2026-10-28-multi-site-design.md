# Multi-site support — design

## Goal
Manage several sites in one app. Each site keeps its own base URL, alias hosts, page patterns, favorites, history and cached resources. Cache size limit and "protect favorites" stay global. The domain-independence guarantee of the single-site design still holds per site.

## Data model
- Room table `site(id TEXT PK uuid, name, baseUrl, aliasHosts (comma-joined, lowercase), seriesPattern, episodePattern, createdAt)`.
- `favorite` and `read_history` gain `siteId`; primary key becomes `(siteId, key)`.
- Global settings (DataStore): `cacheLimitMb`, `protectFavorites`, `currentSiteId`. Per-site fields leave DataStore.
- `UrlKeyer.key` stays host-independent. Resource cache key = `siteId + ":" + urlKey`.

## Request handling
- `SiteRegistry` (snapshot, `@Volatile`, refreshed by a collector on `site` table) maps host -> Site (base host or alias). Lookup never blocks the request path.
- `CachingWebViewClient` resolves the Site per request; unknown host -> pass through (as today). Resolution to the current base uses that Site's config.
- Validation on save: a host (base or alias) may belong to only one site; duplicate -> rejected with inline error.
- Cookie fix (final-review finding): OkHttp client uses `followRedirects(false)`; the client follows redirects manually, checking each hop's host is a known host of the same site before sending `Cookie`; otherwise no cookie, no cache, return null. Cookie/Set-Cookie behaviour otherwise unchanged.

## UI
- Top bar site selector (dropdown) in Browser and Library; selection persists in `currentSiteId`. Browser/Library show data for the selected site.
- Settings: site list with add / edit / delete. Edit form = existing per-site fields (name, base URL, aliases, patterns). Delete (confirm dialog) removes the site's favorites, history and cache entries.
- Changing a site's base URL still adds the old host to its aliases and never clears cache.
- Library: opening an item switches to its site automatically.
- First launch with no site: Browser shows an "add a site" prompt.

## Migration
- Room migration N -> N+1: create `site`; if the legacy DataStore has a base URL, create one site ("Default") from the legacy fields, with all existing favorites/history assigned to it. Legacy cache files (unprefixed keys) are rebuilt: cache index/keys migrated by wiping unprefixed entries only if rename is not feasible (cache is disposable; favorites/history are not).
- Legacy DataStore per-site keys are read once, then ignored.

## Testing
Unit/Robolectric: registry host lookup + collision rejection; per-site cache isolation (same path, two sites); site delete cascades; migration keeps favorites/history; redirect to other host does not send Cookie; limit/protect stay global.
Manual device acceptance remains with the user.

## Out of scope
Per-site cache limits, site import/export, per-resource favorites protection.
