# Multi-site Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Manage several sites in one app, each with its own base URL, aliases, patterns, favorites, history and cached resources; cache size limit and "protect favorites" stay global.

**Architecture:** Every site gets a UUID `siteId`. Sites live in a Room `site` table; `favorite`/`read_history` rows and resource-cache keys are namespaced by `siteId`. A `SiteRegistry` snapshot maps a request host (base or alias) to its `Site`; the WebView request path only reads snapshots. Legacy single-site settings migrate once into a "Default" site.

**Tech Stack:** Kotlin, Compose material3, Room (KSP), DataStore, OkHttp, Robolectric/JUnit (existing). Existing code under `app/src/main/java/app/webreader/`, tests under `app/src/test/java/app/webreader/`.

**Spec:** `docs/superpowers/specs/2026-10-28-multi-site-design.md` (builds on `2026-10-28-domain-independent-webview-reader-design.md`).

## Global Constraints

- Package `app.webreader`; minSdk 26, compileSdk 34. No new dependencies.
- Cache key = `"<siteId>:" + UrlKeyer.key(url)`; `UrlKeyer.key` stays host-independent and unchanged.
- A host (base host or alias, lowercase) belongs to at most one site; duplicates rejected on save.
- Cache limit (`cacheLimitMb`) and `protectFavorites` stay global; limit ≥ 1 MB.
- WebView request path never blocks (`@Volatile`/StateFlow snapshots only; `runBlocking` only once at container init).
- Changing a site's base URL never clears cache and adds the previous host to that site's aliases.
- Cookies are sent only to hosts of the same site on every redirect hop; cookie-setting responses are not cached (existing behavior).
- Every task leaves `./gradlew :app:testDebugUnitTest :app:assembleDebug` green. Env: `export JAVA_HOME=~/tools/jdk-17.0.21+9 ANDROID_HOME=~/Android/Sdk PATH=$JAVA_HOME/bin:~/tools/gradle-8.9/bin:$PATH`.
- Commits end with `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Review Focus

- Two sites share a path (`/series/1`): cache entries, favorites and history must not collide (T2, T3).
- Same host (or an alias equal to another site's base host) entered for two sites: rejected with a message (T1, T2).
- Redirect from site A to site B's host: no Cookie sent, nothing cached/served (T3).
- Deleting the current or last site: current falls back to first remaining / none; no crash; its favorites, history, cache gone (T1, T2).
- Upgrading from the single-site DB: favorites/history preserved under the Default site; only runs once, never re-created after the user deletes all sites (T2).
- Base URL edited to a different host: old host becomes alias, cache stays readable (T2).

---

### Task 1: Site model and registry

**Files:**
- Create: `domain/Site.kt`, `domain/SiteRegistry.kt`
- Test: `domain/SiteRegistryTest.kt`

**Interfaces:**
- Produces:
  - `data class Site(val id: String, val name: String, val baseUrl: String, val aliasHosts: Set<String>, val seriesPattern: String, val episodePattern: String)` with `val config: SiteConfig` (existing `SiteConfig(baseUrl, aliasHosts)`), `fun classifier(): PageClassifier`, `fun hosts(): Set<String>` (lowercased base host + aliases; empty-safe, never throws).
  - `class SiteRegistry(val sites: List<Site>)` with `fun forHost(host: String): Site?` (case-insensitive), `fun forUrl(url: String): Site?` (null on malformed), `fun byId(id: String): Site?`, `fun conflictingHost(candidate: Site): String?` (first host of `candidate` already owned by another site, ignoring a site with `candidate.id`), `companion fun resolveCurrent(sites: List<Site>, savedId: String?): Site?` (saved if present, else first, else null).

- [ ] **Step 1: Write failing tests** `forHostMatchesBaseAndAliasCaseInsensitive`, `unknownHostAndMalformedUrlReturnNull`, `conflictDetectedAgainstOtherSiteBaseOrAlias`, `siteDoesNotConflictWithItself`, `resolveCurrentFallsBackToFirstThenNull`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SiteRegistryTest'` → FAIL (unresolved).
- [ ] **Step 3: Implement** both files; build a host→Site map once in the constructor.
- [ ] **Step 4: Run** same command → PASS.
- [ ] **Step 5: Commit** `feat: add site model and registry`.

---

### Task 2: Data layer — sites table, per-site records, migration, container

**Files:**
- Create: `data/db/SiteEntity.kt` (`SiteEntity` + `SiteDao`), `data/SiteRepository.kt`, `data/LegacyMigrator.kt`
- Modify: `data/db/Favorite.kt`, `data/db/ReadHistory.kt`, `data/db/Daos.kt`, `data/db/AppDatabase.kt` (version 2 + `MIGRATION_1_2` exported in companion), `data/ResourceCache.kt`, `data/SettingsStore.kt`, `AppContainer.kt`, plus the callers broken by DAO signature changes (`ui/BrowserViewModel.kt`, `ui/LibraryViewModel.kt`, their tests) — adapt minimally with `siteId: () -> String`; behavior of the UI stays single-site until Task 4.
- Test: `data/SiteRepositoryTest.kt`, `data/db/MigrationTest.kt`, `data/LegacyMigratorTest.kt`, updated DAO/VM tests

**Interfaces:**
- Consumes: `Site`, `SiteRegistry` (T1).
- Produces:
  - `SiteEntity(id, name, baseUrl, aliasHosts: String /*comma-joined lowercase*/, seriesPattern, episodePattern, createdAt: Long)` with `toSite()`/`SiteEntity.from(site, createdAt)`; `SiteDao`: `observeAll(): Flow<List<SiteEntity>>` (createdAt ASC), `upsert`, `delete(id)`, `count(): Int`.
  - `Favorite(siteId, key, type, title, addedAt)` PK `(siteId, key)`; `ReadHistory(siteId, key, lastPosition, updatedAt)` PK `(siteId, key)`.
  - `FavoriteDao`: `upsert`, `delete(siteId, key)`, `observeAll(siteId): Flow<List<Favorite>>`, `observeIsFavorite(siteId, key)`, `observeAllRefs(): Flow<List<FavoriteRef>>` (`FavoriteRef(siteId, key)`), `deleteForSite(siteId)`, `reassignLegacy(siteId)` (rows with `siteId=''`). `ReadHistoryDao`: `upsert`, `get(siteId, key)`, `observeRecent(siteId, limit)`, `deleteForSite(siteId)`, `reassignLegacy(siteId)`.
  - `ResourceCache.removePrefix(prefix: String)`.
  - `SettingsStore`: add `currentSiteId: Flow<String?>`, `setCurrentSiteId(id: String)`, `legacyMigrated: Flow<Boolean>`, `setLegacyMigrated()`; existing per-site flows/setters stay until Task 5 (legacy readers).
  - `class SiteRepository(siteDao, favoriteDao, historyDao, cache: ResourceCache, now: () -> Long = System::currentTimeMillis)`: `val sites: Flow<List<Site>>`; `suspend fun add(name: String, baseUrl: String, aliases: String, series: String, episode: String): Result<Site>`; `suspend fun update(id: String, name: String, baseUrl: String, aliases: String, series: String, episode: String): Result<Unit>`; `suspend fun delete(id: String)`.
    - Validation (failure with message): name non-blank; baseUrl http/https with host (scheme+host lowercased when stored); patterns compile; no host conflict via `SiteRegistry.conflictingHost`. Aliases: split on commas/whitespace, trim, lowercase, dedupe, drop the site's own base host.
    - `update` with a changed base host adds the previous host to aliases and never touches the cache.
    - `delete` removes the site row, its favorites, history and cache entries with prefix `"$id:"`.
  - `class LegacyMigrator(settings, siteRepository-or-daos, cache)`: `suspend fun run()` — if `!legacyMigrated` and legacy base URL non-blank: create site "Default" from legacy fields/aliases/patterns, `reassignLegacy(newId)` on both DAOs, `cache.clear()`, `setCurrentSiteId(newId)`; always `setLegacyMigrated()` afterwards.
  - `AppContainer`: add `siteRepository`, `registry: StateFlow<SiteRegistry>` + `registrySnapshot: () -> SiteRegistry`, `currentSite: StateFlow<Site?>` (via `SiteRegistry.resolveCurrent(sites, saved)`), `currentSiteId: () -> String` (`""` when none), `suspend fun selectSite(id: String)`. Protected cache keys become `"<siteId>:<key>"` of all favorites when `protectFavorites`. Run `LegacyMigrator` once in init with `runBlocking` before snapshots are seeded. Old `siteConfig*`/`classifier*`/`keyer` stay until Task 4.

- [ ] **Step 1: Write failing tests**
  - `SiteRepositoryTest`: `sameHostRejectedAcrossSites`, `aliasEqualToOtherBaseRejected`, `invalidUrlAndRegexRejected`, `changingBaseHostAddsOldHostAndKeepsCache`, `deleteCascadesFavoritesHistoryAndCacheOfThatSiteOnly`, `samePathInTwoSitesDoesNotCollide` (favorites + history).
  - `MigrationTest`: build a v1 database file with raw SQLite (old tables + rows), open with Room + `MIGRATION_1_2`, assert rows exist with `siteId=''` and the `site` table exists.
  - `LegacyMigratorTest`: `legacySettingsBecomeDefaultSiteAndRecordsMoved`, `runsOnlyOnce` (delete all sites, run again → no new site), `blankLegacyBaseCreatesNothing`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SiteRepositoryTest' --tests '*MigrationTest' --tests '*LegacyMigratorTest'` → FAIL.
- [ ] **Step 3: Implement** schema/DAOs/migration (SQL rebuilds `favorite` and `read_history` with `siteId TEXT NOT NULL DEFAULT ''` and the composite PK, plus `CREATE TABLE site`), `removePrefix`, SettingsStore additions, `SiteRepository`, `LegacyMigrator`, container wiring; adapt BrowserViewModel/LibraryViewModel and their tests to the new DAO signatures with an injected `siteId: () -> String`.
- [ ] **Step 4: Run** full `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5: Commit** `feat: store sites and namespace records by site`.

---

### Task 3: Per-site interception, redirect-safe cookies

**Files:**
- Modify: `domain/UrlKeyer.kt`, `web/CachingWebViewClient.kt`, `ui/BrowserScreen.kt` (client construction only), existing tests using `UrlKeyer { config }` / the client
- Test: `web/CachingWebViewClientTest.kt`, `domain/UrlKeyerTest.kt`

**Interfaces:**
- Consumes: `SiteRegistry` (T1); container `registrySnapshot` (T2).
- Produces:
  - `class UrlKeyer` (no constructor args): `fun key(url: String): String` (unchanged behavior), `fun resolve(baseUrl: String, key: String): String`.
  - `CachingWebViewClient(keyer: UrlKeyer, registry: () -> SiteRegistry, cache: ResourceCache, http: OkHttpClient, maxCacheableBytes: Long = MAX_CACHEABLE_BYTES, onPageStarted: (String) -> Unit = {}, cookies: (String) -> String? = <CookieManager>, setCookie: (String, String) -> Unit = <CookieManager>, onPageFinished: (String) -> Unit)`.

- [ ] **Step 1: Write failing tests** (keep all existing behavior tests, adapted): `sameKeyDifferentSitesIsolatedInCache` (two sites, same path, different bodies), `aliasHostServedFromThatSitesBase`, `redirectToOtherSitesHostNotServedNotCachedNoCookie` (assert the second request, if any, carries no `Cookie` header), `redirectWithinSiteKeepsCookieOnlyForSiteHosts`, `tooManyRedirectsReturnsNull` (limit 5 hops).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*CachingWebViewClientTest' --tests '*UrlKeyerTest'` → FAIL.
- [ ] **Step 3: Implement.** Resolve `site = registry().forHost(host)` (null → pass through); cache key `"${site.id}:${keyer.key(url)}"`; fetch against `keyer.resolve(site.baseUrl, key)`. Use `http.newBuilder().followRedirects(false).build()` and follow redirects manually (≤5): each hop's host must satisfy `site.hosts()`, else abort (null, no cache); attach `cookies(hopUrl)` per hop; keep Set-Cookie write-back and no-cache-on-Set-Cookie behavior.
- [ ] **Step 4: Run** full `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5: Commit** `feat: site-aware interception with redirect-safe cookies`.

---

### Task 4: Per-site Browser and Library UI with site selector

**Files:**
- Modify: `ui/BrowserViewModel.kt`, `ui/BrowserScreen.kt`, `ui/LibraryViewModel.kt`, `ui/LibraryScreen.kt`, `ui/MainActivity.kt`, `AppContainer.kt` (remove `siteConfig`, `siteConfigFlow`, `classifier`, `classifierFlow`, `keyer` config usage); create `ui/SiteSelector.kt`
- Test: `ui/BrowserViewModelTest.kt`, `ui/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: `SiteRegistry`, `UrlKeyer` (T3), container `currentSite`, `currentSiteId`, `selectSite`, `registrySnapshot` (T2).
- Produces:
  - `BrowserViewModel(favoriteDao, historyDao, keyer: UrlKeyer, registry: () -> SiteRegistry, now: () -> Long)`: records are written under `registry().forUrl(url)` (unknown host → nothing recorded); `pageType(url)` uses that site's `classifier()`; `isFavorite(url)` observes `(siteId, key)`.
  - `LibraryViewModel(favoriteDao, historyDao, keyer, registry: () -> SiteRegistry, currentSiteId: StateFlow<String>)`: `favorites`/`recent`/`favoritesOfType(type)` follow `currentSiteId` (flatMapLatest); `urlFor(key)` resolves against the current site's base URL.
  - `@Composable SiteSelector(sites: List<Site>, current: Site?, onSelect: (String) -> Unit)` — dropdown in the top bar of Browser and Library.
  - `MainActivity.openInBrowser(siteId: String, url: String)`: selects the site, then issues the existing `OpenRequest`.

- [ ] **Step 1: Write failing tests:** `recordsGoToTheSiteOfTheUrl`, `unknownHostRecordsNothing`, `pageTypeUsesThatSitesPatterns`, `libraryFollowsCurrentSite` (switch current id → lists change), `urlForUsesCurrentSiteBase`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*BrowserViewModelTest' --tests '*LibraryViewModelTest'` → FAIL.
- [ ] **Step 3: Implement** ViewModels, selector, MainActivity wiring; Browser loads the current site's `baseUrl` when the site changes and shows an "add a site" prompt (button → Settings tab) when there is no site; remove the container's old single-site API.
- [ ] **Step 4: Run** full `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5: Commit** `feat: per-site browser and library with site selector`.

---

### Task 5: Settings — site management

**Files:**
- Modify: `ui/SettingsViewModel.kt`, `ui/SettingsScreen.kt`, `data/SettingsStore.kt` (remove legacy per-site setters; keep legacy readers used by `LegacyMigrator`), `ui/MainActivity.kt` if wiring changes
- Create: `ui/SiteEditScreen.kt` (or section in SettingsScreen)
- Test: `ui/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SiteRepository` (T2).
- Produces: `SettingsViewModel` keeps `saveCache`, `clearCache`, `cacheSizeBytes`, cache limit/protect flows; adds `val sites: Flow<List<Site>>`, `suspend fun addSite(name, baseUrl, aliases, series, episode): Result<Site>`, `suspend fun updateSite(id, name, baseUrl, aliases, series, episode): Result<Unit>`, `suspend fun deleteSite(id: String)`; removes `saveBaseUrl`/`saveAliasHosts`/`savePatterns`.

- [ ] **Step 1: Write failing tests:** `addSiteDuplicateHostSurfacedAsFailure`, `deleteSiteRemovesItsRecords`, `cacheLimitStillGlobalAndAtLeastOne`; drop tests of removed methods.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SettingsViewModelTest'` → FAIL.
- [ ] **Step 3: Implement.** UI: site list (name + base host) with Add/Edit/Delete; edit form fields name, base URL, aliases (comma separated), series/episode patterns with inline errors from the `Result`; delete behind a confirm dialog stating favorites, history and cache of that site are removed; global cache section (limit, protect favorites, size, clear all) unchanged.
- [ ] **Step 4: Run** full `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5: Commit** `feat: manage multiple sites in settings`.

---

## Self-Review Notes

- Spec coverage: data model/migration (T2), request handling + cookie fix (T3), selector/library/browser (T4), settings CRUD + delete cascade (T2/T5), global limit/protect (T2/T5), testing list (each task).
- Spec clarification: Library shows the selected site only, so "auto switch on open" is realized by `openInBrowser(siteId, url)` selecting the item's site.
- Manual device acceptance remains with the user (no device available).
