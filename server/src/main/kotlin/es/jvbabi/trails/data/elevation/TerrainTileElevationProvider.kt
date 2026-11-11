package es.jvbabi.trails.data.elevation

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Downloads one encoded elevation tile.
 *
 * Returns null if the source has no such tile; throws if it could not be asked — a
 * network error, a server error — which is retried later.
 */
fun interface TileFetcher {
    suspend fun fetch(key: TileKey): ByteArray?
}

/**
 * Elevation from Terrarium tiles (see [TerrariumTile]) downloaded on demand and
 * cached for good: on disk under [cacheDirectory] as the PNGs the source serves, laid
 * out as `<zoom>/<x>/<y>.png`, and the most recently used [memoryTiles] decoded in
 * memory.
 *
 * Every tile is downloaded at most once. Concurrent requests for the same tile wait for
 * the one download in progress, and a tile is written to a temporary file first and
 * moved into place, so a crash never leaves a truncated tile behind.
 *
 * When a download fails, all downloads pause for [retryDelay] — a server without
 * network access would otherwise try again for every batch. A tile the source does
 * not have is not asked for again for [retryDelay] either. Meanwhile the lookups answer
 * null where the tile is missing, which the optimizer reads as flat ground.
 */
class TerrainTileElevationProvider(
    private val cacheDirectory: File,
    private val fetcher: TileFetcher,
    private val zoom: Int = DEFAULT_ZOOM,
    private val memoryTiles: Int = DEFAULT_MEMORY_TILES,
    private val retryDelay: Duration = DEFAULT_RETRY_DELAY,
    private val clock: Clock = Clock.System,
    private val onClose: () -> Unit = {},
) : ElevationProvider {

    private val logger = KtorSimpleLogger("TerrainTileElevationProvider")

    /** Decoded tiles, least recently used first. Guarded by itself. */
    private val memory = object : LinkedHashMap<TileKey, TerrariumTile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, TerrariumTile>) = size > memoryTiles
    }

    /** One lock per tile, so a tile is loaded once however many callers want it. */
    private val tileLocks = ConcurrentHashMap<TileKey, Mutex>()

    /** Tiles the source does not have, and until when they are not asked for again. */
    private val missingUntil = ConcurrentHashMap<TileKey, Instant>()

    /** Until when downloads pause after a failure, see the class description. */
    @Volatile
    private var downloadsBlockedUntil: Instant? = null

    override suspend fun lookupFor(points: List<Pair<Double, Double>>): ElevationLookup {
        val keys = points.flatMapTo(mutableSetOf()) { (latitude, longitude) ->
            TerrariumGrid.tilesAround(latitude, longitude, zoom)
        }
        if (keys.isEmpty()) return ElevationLookup.NONE

        // Held for the whole lookup, so the memory cache may evict them meanwhile.
        val tiles = keys.mapNotNull { key -> tile(key)?.let { key to it } }.toMap()
        if (tiles.isEmpty()) return ElevationLookup.NONE

        return ElevationLookup { latitude, longitude ->
            TerrariumGrid.elevationAt(latitude, longitude, zoom, tiles::get)
        }
    }

    /** The tile from memory, disk or the source — in that order — or null if unavailable. */
    internal suspend fun tile(key: TileKey): TerrariumTile? {
        fromMemory(key)?.let { return it }

        return tileLocks.computeIfAbsent(key) { Mutex() }.withLock {
            // Whoever held the lock before may just have loaded it.
            fromMemory(key) ?: (fromDisk(key) ?: download(key))?.also { tile ->
                synchronized(memory) { memory[key] = tile }
            }
        }
    }

    private fun fromMemory(key: TileKey): TerrariumTile? = synchronized(memory) { memory[key] }

    private fun fileOf(key: TileKey): File = cacheDirectory.resolve("${key.zoom}/${key.x}/${key.y}.png")

    private suspend fun fromDisk(key: TileKey): TerrariumTile? = withContext(Dispatchers.IO) {
        val file = fileOf(key)
        if (!file.isFile) return@withContext null

        try {
            TerrariumTile.decode(file.readBytes())
        } catch (exception: Exception) {
            // Not written by us in one piece; fetch it again.
            logger.warn("Discarding unreadable elevation tile ${file.path}: ${exception.message}")
            file.delete()
            null
        }
    }

    private suspend fun download(key: TileKey): TerrariumTile? {
        val now = clock.now()
        downloadsBlockedUntil?.let { if (now < it) return null }
        missingUntil[key]?.let { if (now < it) return null }

        val bytes = try {
            fetcher.fetch(key)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            downloadsBlockedUntil = now + retryDelay
            logger.warn(
                "Could not download elevation tile $key, classifying without elevation and " +
                        "retrying in $retryDelay: ${exception.message}"
            )
            return null
        }

        val tile = bytes?.let { runCatching { TerrariumTile.decode(it) }.getOrNull() }
        if (bytes == null || tile == null) {
            missingUntil[key] = now + retryDelay
            logger.warn("No usable elevation tile $key at the source, retrying in $retryDelay")
            return null
        }

        missingUntil.remove(key)
        withContext(Dispatchers.IO) { store(key, bytes) }
        return tile
    }

    /** Writes the tile next to its final place and moves it there in one step. */
    private fun store(key: TileKey, bytes: ByteArray) {
        val target = fileOf(key)
        try {
            target.parentFile.mkdirs()
            val temporary = File.createTempFile("${key.y}.", ".part", target.parentFile)
            try {
                temporary.writeBytes(bytes)
                try {
                    Files.move(
                        temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        } catch (exception: Exception) {
            // The tile is still used from memory; it is just downloaded again after a restart.
            logger.warn("Could not cache elevation tile $key in ${target.path}: ${exception.message}")
        }
    }

    override fun close() = onClose()

    companion object {
        /**
         * About 38 m per pixel at the equator and 24 m at 52° north — as fine as the
         * underlying SRTM / EU-DEM data, and one tile still covers 6–10 km.
         */
        const val DEFAULT_ZOOM = 12

        /** A decoded tile takes 256 KiB, so this caps the memory cache at 16 MiB. */
        const val DEFAULT_MEMORY_TILES = 64

        val DEFAULT_RETRY_DELAY: Duration = 15.minutes
    }
}
