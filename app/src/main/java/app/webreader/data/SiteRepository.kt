package app.webreader.data

import app.webreader.data.db.FavoriteDao
import app.webreader.data.db.ReadHistoryDao
import app.webreader.data.db.SiteDao
import app.webreader.data.db.SiteEntity
import app.webreader.domain.Site
import app.webreader.domain.SiteRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.UUID

class SiteRepository(
    private val siteDao: SiteDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadHistoryDao,
    private val cache: ResourceCache,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    val sites: Flow<List<Site>> = siteDao.observeAll().map { list -> list.map { it.toSite() } }

    suspend fun add(
        name: String, baseUrl: String, aliases: String, series: String, episode: String,
    ): Result<Site> = mutex.withLock {
        val existing = siteDao.observeAll().first()
        val base = normalizeBase(baseUrl) ?: return Result.failure(IllegalArgumentException("Invalid base URL"))
        val candidate = build(UUID.randomUUID().toString(), name, base, parseAliases(aliases, base), series, episode)
        validate(candidate, existing.map { it.toSite() })?.let { return Result.failure(IllegalArgumentException(it)) }
        siteDao.upsert(SiteEntity.from(candidate, now()))
        Result.success(candidate)
    }

    suspend fun update(
        id: String, name: String, baseUrl: String, aliases: String, series: String, episode: String,
    ): Result<Unit> = mutex.withLock {
        val existing = siteDao.observeAll().first()
        val current = existing.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Site not found"))
        val base = normalizeBase(baseUrl) ?: return Result.failure(IllegalArgumentException("Invalid base URL"))
        val aliasSet = LinkedHashSet(parseAliases(aliases, base))
        val oldHost = hostOf(current.baseUrl)
        if (oldHost != null && oldHost != hostOf(base)) aliasSet += oldHost
        val candidate = build(id, name, base, aliasSet, series, episode)
        validate(candidate, existing.map { it.toSite() })?.let { return Result.failure(IllegalArgumentException(it)) }
        siteDao.upsert(SiteEntity.from(candidate, current.createdAt))
        Result.success(Unit)
    }

    suspend fun delete(id: String) = mutex.withLock {
        inTransaction {
            siteDao.delete(id)
            favoriteDao.deleteForSite(id)
            historyDao.deleteForSite(id)
        }
        cache.removePrefix("$id:")
    }

    private fun build(id: String, name: String, base: String, aliases: Set<String>, series: String, episode: String) =
        Site(id, name.trim(), base, aliases, series, episode)

    private fun validate(candidate: Site, others: List<Site>): String? {
        if (candidate.name.isBlank()) return "Name is required"
        if (runCatching {
                if (candidate.seriesPattern.isNotEmpty()) Regex(candidate.seriesPattern)
                if (candidate.episodePattern.isNotEmpty()) Regex(candidate.episodePattern)
            }.isFailure) {
            return "Invalid pattern"
        }
        SiteRegistry(others).conflictingHost(candidate)?.let { return "Host already used by another site: $it" }
        return null
    }

    private fun parseAliases(raw: String, base: String): Set<String> {
        val own = hostOf(base)
        return raw.split(Regex("[,\\s]+")).map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != own }.toCollection(LinkedHashSet())
    }

    private fun normalizeBase(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$host$port$path"
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
}
