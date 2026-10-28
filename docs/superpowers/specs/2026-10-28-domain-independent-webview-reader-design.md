# Domain-Independent WebView Reader — Design

## Intent
Android app wrapping a comic/webtoon gallery site in a WebView. Goals:
1. Cache survives changes of the site's base domain.
2. Personal records (favorites, reading history) tied to the site's content, not its domain.

Out of scope (YAGNI): memos, tags, backup/export, native HTML parsing/reader, cross-platform.

## Decisions
- Platform: Kotlin + Jetpack Compose, Room, OkHttp. Android only.
- Approach: intercept WebView requests (`shouldInterceptRequest`) and serve from an app-owned disk cache keyed by a host-independent key.
- Rejected: built-in HTTP cache with URL rewriting (cannot read old-domain entries); local localhost proxy (too heavy, HTTPS/cookie complexity).

## Architecture
```
UI (Compose)
 ├─ BrowserScreen  : WebView + favorite/read-state overlay
 ├─ LibraryScreen  : favorites (series/episode), recent reads
 └─ SettingsScreen : base URL, host aliases, page patterns, cache limit/clear
Domain
 ├─ UrlKeyer       : URL -> host-independent key (path + normalized query)
 ├─ SiteConfig     : current base URL, alias hosts (CDN, old domains)
 └─ PageClassifier : path -> SERIES | EPISODE | OTHER via user-set regexes
Data
 ├─ ResourceCache  : disk cache (OkHttp fetch, keyed storage, LRU + size cap)
 └─ Room DB        : favorites, read_history
```

## Behavior
- **Request flow:** `shouldInterceptRequest` -> `UrlKeyer.key(url)` -> cache hit: serve; miss: fetch from current base URL (alias hosts rewritten to current/alias host), store, serve.
- **Domain change:** user edits base URL in settings; all old/alias hosts map to the same keys, so cache and records remain valid.
- **Cached:** GET only; images (kept until evicted), CSS/JS, HTML (offline fallback). POST, Range requests, and cookie/login flows pass through uncached.
- **PageClassifier:** no built-in site knowledge. Two user-configured regexes (series, episode); empty by default.
- **Records:**
  - `favorites(key PK, type SERIES|EPISODE, title, addedAt)`
  - `read_history(key PK, lastPosition, updatedAt)`
  - Keys are host-independent.
- **Last position:** scroll position captured via injected JS, restored on revisit.
- **Cache management:** max size (MB) setting, LRU eviction, clear-all, option to protect favorited items from eviction.

## Error handling
- Network failure: serve cached copy if present; otherwise default WebView error page.
- Invalid regex in settings: reject with inline error; keep previous value.

## Testing
- Unit: `UrlKeyer` (host/alias/query normalization), `ResourceCache` LRU and protection, Room DAOs, `PageClassifier`.
- Manual: browse an episode, change base URL, confirm it opens offline from cache and favorites/history persist.

## Open assumptions
- The site's content paths are stable across domain changes (only the host changes).
- The user supplies series/episode regexes matching the site.
