# Domain-Independent WebView Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android app that wraps a comic/webtoon site in a WebView, keeps its cache valid across base-domain changes, and stores favorites/read history keyed independently of the domain.

**Architecture:** `WebViewClient.shouldInterceptRequest` computes a host-independent key (`UrlKeyer`) and serves from an app-owned disk cache (`ResourceCache`); misses are fetched via OkHttp from the current base URL. Room stores favorites and read history under the same keys.

**Tech Stack:** Kotlin, Jetpack Compose, Room, DataStore, OkHttp, JUnit4, Robolectric (Room/DAO tests), MockWebServer.

**Spec:** `docs/superpowers/specs/2026-10-28-domain-independent-webview-reader-design.md`

## Global Constraints

- Android only; Kotlin + Jetpack Compose; Room; OkHttp.
- Package: `app.webreader`. minSdk 26, compileSdk 34.
- Cache key = host-independent (path + normalized query); records use the same key.
- Cache only GET; images, CSS/JS, HTML. POST, Range requests, and cookie/login flows pass through uncached.
- `PageClassifier`: two user-set regexes (series, episode), empty by default; invalid regex rejected with inline error, previous value kept.
- Favorite types: `SERIES` | `EPISODE`.
- Cache management: max size (MB), LRU eviction, clear-all, option to protect favorited keys from eviction.
- Out of scope: memos, tags, export, native parsing.

## Review Focus

- Query params in different order (`?a=1&b=2` vs `?b=2&a=1`) -> same key.
- Keys differing only by host alias still match; other query params are never stripped.
- Old-domain URL opened after base URL change -> resolves to the same key and loads from cache.
- Cache at size limit with all remaining entries protected -> new writes skipped, no crash or infinite eviction loop.
- Network down + cache miss -> request fails cleanly (WebView error page), no crash.
- Request with `Range` header or non-GET method -> never served from or written to cache.

## File Structure

```
app/src/main/java/app/webreader/
  domain/UrlKeyer.kt, SiteConfig.kt, PageClassifier.kt
  data/ResourceCache.kt, SettingsStore.kt, db/{AppDatabase,Favorite,ReadHistory,Daos}.kt
  web/CachingWebViewClient.kt
  ui/BrowserScreen.kt, LibraryScreen.kt, SettingsScreen.kt, MainActivity.kt
app/src/test/java/app/webreader/...   (mirrors main)
```

---

### Task 1: Project scaffold + UrlKeyer

**Files:**
- Create: Gradle project (Compose app module `app`), `domain/SiteConfig.kt`, `domain/UrlKeyer.kt`
- Test: `app/src/test/java/app/webreader/domain/UrlKeyerTest.kt`

**Interfaces:**
- Produces:
  - `data class SiteConfig(val baseUrl: String, val aliasHosts: Set<String>)` with `fun isKnownHost(host: String): Boolean` (true for baseUrl host or any alias).
  - `class UrlKeyer(private val config: () -> SiteConfig)` with `fun key(url: String): String` and `fun resolve(key: String): String` (key -> full URL on current base host).

- [ ] **Step 1: Scaffold** the Compose app (Android Studio template equivalent); add Room, DataStore, OkHttp, Robolectric, MockWebServer deps. Confirm `./gradlew :app:testDebugUnitTest` runs with zero tests.
- [ ] **Step 2: Write failing tests** in `UrlKeyerTest`:
  - `sameKeyAcrossHosts`: `key("https://a.com/ep/1") == key("https://b.net/ep/1")`
  - `queryOrderIgnored`: `key("https://a.com/x?b=2&a=1") == key("https://a.com/x?a=1&b=2")` and equals `"/x?a=1&b=2"`
  - `trailingSlashAndFragmentNormalized`: `key("https://a.com/x/#top") == key("https://a.com/x")`
  - `resolveUsesCurrentBase`: with base `https://new.com`, `resolve("/ep/1") == "https://new.com/ep/1"`
- [ ] **Step 3: Run** `./gradlew :app:testDebugUnitTest --tests '*UrlKeyerTest'` -> FAIL.
- [ ] **Step 4: Implement** `SiteConfig` and `UrlKeyer`; key = path (trailing slash trimmed, root stays `/`) + `?` + query params sorted by name then value; fragment dropped.
- [ ] **Step 5: Run** same command -> PASS.
- [ ] **Step 6: Commit** `feat: scaffold app and add UrlKeyer`.

### Task 2: SettingsStore

**Files:**
- Create: `data/SettingsStore.kt`
- Test: `data/SettingsStoreTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `SiteConfig`.
- Produces: `class SettingsStore(context: Context)` with
  - `val siteConfig: Flow<SiteConfig>`; `suspend fun setBaseUrl(url: String)`; `suspend fun setAliasHosts(hosts: Set<String>)`
  - `val seriesPattern: Flow<String>`, `val episodePattern: Flow<String>`; `suspend fun setPatterns(series: String, episode: String): Result<Unit>` (failure if either is non-empty and not a valid regex; stored values unchanged on failure)
  - `val cacheLimitMb: Flow<Int>` (default 500); `suspend fun setCacheLimitMb(mb: Int)`; `val protectFavorites: Flow<Boolean>` (default true); `suspend fun setProtectFavorites(v: Boolean)`

- [ ] **Step 1: Write failing tests:** `defaults`, `baseUrlRoundTrip`, `invalidRegexRejectedAndPreviousKept` (set `/series/\d+`, then `"("` -> `isFailure`, flow still emits the valid value).
- [ ] **Step 2: Run** `--tests '*SettingsStoreTest'` -> FAIL.
- [ ] **Step 3: Implement** with Preferences DataStore.
- [ ] **Step 4: Run** -> PASS.
- [ ] **Step 5: Commit** `feat: add SettingsStore`.

### Task 3: PageClassifier

**Files:**
- Create: `domain/PageClassifier.kt`
- Test: `domain/PageClassifierTest.kt`

**Interfaces:**
- Produces: `enum class PageType { SERIES, EPISODE, OTHER }`; `class PageClassifier(seriesPattern: String, episodePattern: String)` with `fun classify(key: String): PageType` (episode checked before series; empty pattern never matches).

- [ ] **Step 1: Write failing tests:** `emptyPatternsAreOther`; `episodeWinsWhenBothMatch`; `matchesAgainstKeyNotHost` (pattern `^/series/\d+`, key `/series/12` -> SERIES).
- [ ] **Step 2: Run** `--tests '*PageClassifierTest'` -> FAIL.
- [ ] **Step 3: Implement** using `Regex.containsMatchIn`.
- [ ] **Step 4: Run** -> PASS.
- [ ] **Step 5: Commit** `feat: add PageClassifier`.

### Task 4: ResourceCache

**Files:**
- Create: `data/ResourceCache.kt`
- Test: `data/ResourceCacheTest.kt` (JUnit + temp dir)

**Interfaces:**
- Produces: `data class CachedResource(val mimeType: String, val encoding: String?, val bytes: ByteArray)`;
  `class ResourceCache(dir: File, maxBytes: () -> Long, protectedKeys: () -> Set<String>)` with
  - `fun get(key: String): CachedResource?` (updates recency)
  - `fun put(key: String, res: CachedResource): Boolean` (false if skipped because no unprotected space can be freed)
  - `fun clear()`; `fun sizeBytes(): Long`

- [ ] **Step 1: Write failing tests:** `putThenGet`; `evictsLeastRecentlyUsedOverLimit`; `getRefreshesRecency`; `protectedKeysNotEvicted`; `allProtectedAtLimitSkipsPutWithoutLooping` (returns false, existing entries intact); `survivesRestart` (new instance on same dir sees entries); `clearRemovesAll`.
- [ ] **Step 2: Run** `--tests '*ResourceCacheTest'` -> FAIL.
- [ ] **Step 3: Implement** with files named by SHA-256 of key plus a metadata sidecar (mime, encoding, key, lastAccess); eviction scans by lastAccess skipping protected keys.
- [ ] **Step 4: Run** -> PASS.
- [ ] **Step 5: Commit** `feat: add LRU ResourceCache`.

### Task 5: Room database

**Files:**
- Create: `data/db/Favorite.kt`, `ReadHistory.kt`, `Daos.kt`, `AppDatabase.kt`
- Test: `data/db/DaosTest.kt` (Robolectric, in-memory DB)

**Interfaces:**
- Produces:
  - `@Entity Favorite(@PrimaryKey key: String, type: String /*SERIES|EPISODE*/, title: String, addedAt: Long)`
  - `@Entity ReadHistory(@PrimaryKey key: String, lastPosition: Int, updatedAt: Long)`
  - `FavoriteDao`: `suspend upsert(Favorite)`, `suspend delete(key: String)`, `fun observeAll(): Flow<List<Favorite>>`, `fun observeIsFavorite(key: String): Flow<Boolean>`, `suspend allKeys(): List<String>`
  - `ReadHistoryDao`: `suspend upsert(ReadHistory)`, `suspend get(key: String): ReadHistory?`, `fun observeRecent(limit: Int): Flow<List<ReadHistory>>`

- [ ] **Step 1: Write failing tests:** `favoriteToggle`; `favoritesKeepBothTypes`; `historyUpsertOverwritesPosition`; `recentOrderedByUpdatedAtDesc`.
- [ ] **Step 2: Run** `--tests '*DaosTest'` -> FAIL.
- [ ] **Step 3: Implement** entities, DAOs, `AppDatabase`.
- [ ] **Step 4: Run** -> PASS.
- [ ] **Step 5: Commit** `feat: add Room favorites and history`.

### Task 6: CachingWebViewClient

**Files:**
- Create: `web/CachingWebViewClient.kt`
- Test: `web/CachingWebViewClientTest.kt` (Robolectric + MockWebServer)

**Interfaces:**
- Consumes: `UrlKeyer`, `ResourceCache`, `SiteConfig`, `OkHttpClient`.
- Produces: `class CachingWebViewClient(keyer: UrlKeyer, cache: ResourceCache, http: OkHttpClient, config: () -> SiteConfig, onPageFinished: (url: String) -> Unit) : WebViewClient` overriding `shouldInterceptRequest(view, request): WebResourceResponse?`.
  Rules: return `null` (passthrough) for non-GET, `Range` header, or hosts where `!config().isKnownHost(host)`; on hit serve cache; on miss fetch `keyer.resolve(key)` via OkHttp, cache 2xx responses only, and return them; on IOException with no cache return `null`.

- [ ] **Step 1: Write failing tests:** `hitServedWithoutNetwork`; `missFetchesFromCurrentBaseAndStores`; `domainChangeStillHits` (cache filled via host A, base switched to host B, server stopped -> still served); `nonGetAndRangePassThrough` (nothing stored); `non2xxNotCached`; `networkFailureWithoutCacheReturnsNull`.
- [ ] **Step 2: Run** `--tests '*CachingWebViewClientTest'` -> FAIL.
- [ ] **Step 3: Implement**; copy upstream `Content-Type` into mime/encoding; call `onPageFinished` from `WebViewClient.onPageFinished`.
- [ ] **Step 4: Run** -> PASS.
- [ ] **Step 5: Commit** `feat: add caching WebViewClient`.

### Task 7: BrowserScreen

**Files:**
- Create: `ui/BrowserScreen.kt`, `ui/BrowserViewModel.kt`, `ui/MainActivity.kt`, `AppContainer.kt` (manual wiring of Tasks 1-6)
- Test: `ui/BrowserViewModelTest.kt`

**Interfaces:**
- Consumes: DAOs, `PageClassifier`, `UrlKeyer`, `SettingsStore`.
- Produces: `class BrowserViewModel` with `fun onPageFinished(url: String, title: String?)` (records `ReadHistory` for classified pages), `fun savePosition(url: String, y: Int)`, `suspend fun restoreTarget(url: String): Int?`, `fun toggleFavorite(url: String, title: String)` (type from classifier; `OTHER` pages are a no-op), `fun isFavorite(url: String): Flow<Boolean>`.

- [ ] **Step 1: Write failing tests:** `otherPagesNotFavoritable`; `toggleStoresHostIndependentKey`; `oldDomainUrlMapsToSameFavorite`; `savePositionThenRestore`.
- [ ] **Step 2: Run** `--tests '*BrowserViewModelTest'` -> FAIL.
- [ ] **Step 3: Implement** ViewModel and `BrowserScreen` (WebView with `CachingWebViewClient`, JS enabled, star button, `scrollY` saved on pause/navigation and restored after page finish, back via `canGoBack`). Start URL = `config.baseUrl`.
- [ ] **Step 4: Run** -> PASS; manual: `./gradlew installDebug`, favorite a page, kill app, reopen -> star persists, position restores.
- [ ] **Step 5: Commit** `feat: add browser screen with favorites and position restore`.

### Task 8: LibraryScreen

**Files:**
- Create: `ui/LibraryScreen.kt`, `ui/LibraryViewModel.kt`
- Test: `ui/LibraryViewModelTest.kt`

**Interfaces:**
- Produces: `class LibraryViewModel` with `val favorites: Flow<List<Favorite>>`, `val recent: Flow<List<ReadHistory>>`, `fun urlFor(key: String): String` (via `UrlKeyer.resolve`, so opening uses the current domain).

- [ ] **Step 1: Write failing tests:** `urlForUsesCurrentBase`; `favoritesCanBeFilteredByType`.
- [ ] **Step 2: Run** `--tests '*LibraryViewModelTest'` -> FAIL.
- [ ] **Step 3: Implement** ViewModel and Compose screen (tabs: Favorites [Series/Episodes], Recent); tapping navigates to `BrowserScreen` with `urlFor(key)`.
- [ ] **Step 4: Run** -> PASS.
- [ ] **Step 5: Commit** `feat: add library screen`.

### Task 9: SettingsScreen

**Files:**
- Create: `ui/SettingsScreen.kt`, `ui/SettingsViewModel.kt`
- Test: `ui/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `ResourceCache`.
- Produces: `class SettingsViewModel` with `suspend fun saveBaseUrl(url: String): Result<Unit>` (must be valid http/https), `suspend fun savePatterns(series: String, episode: String): Result<Unit>`, `suspend fun saveCache(limitMb: Int, protectFavorites: Boolean)`, `fun clearCache()`, `val cacheSizeBytes: Flow<Long>`.

- [ ] **Step 1: Write failing tests:** `nonHttpBaseUrlRejected`; `invalidRegexSurfacedAsFailure`; `changingBaseUrlDoesNotClearCache`; `clearCacheEmptiesIt`.
- [ ] **Step 2: Run** `--tests '*SettingsViewModelTest'` -> FAIL.
- [ ] **Step 3: Implement** ViewModel and Compose form with inline errors; add navigation among Browser / Library / Settings in `MainActivity`.
- [ ] **Step 4: Run** full `./gradlew :app:testDebugUnitTest` -> all PASS.
- [ ] **Step 5: Manual acceptance:** browse an episode; change base URL in Settings; disable network; reopen the episode -> loads from cache; favorites and history intact.
- [ ] **Step 6: Commit** `feat: add settings screen`.

---

## Self-Review Notes

- Spec coverage: key independence (T1), settings/aliases/patterns/limits (T2), classifier (T3), LRU + protection + clear (T4), records (T5), interception rules (T6), position restore and favorites for both types (T7), library (T8), settings UI + domain-change acceptance (T9).
- Signatures are consistent across tasks (`SiteConfig`, `UrlKeyer.key/resolve`, `ResourceCache.get/put/clear`).
