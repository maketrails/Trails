package es.jvbabi.trails

import es.jvbabi.trails.data.elevation.TerrainTileElevationProvider
import es.jvbabi.trails.data.elevation.TerrariumGrid
import es.jvbabi.trails.data.elevation.TerrariumTile
import es.jvbabi.trails.data.elevation.TileFetcher
import es.jvbabi.trails.data.elevation.TileKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Decoding and interpolating Terrarium tiles, and how [TerrainTileElevationProvider]
 * caches them. The tiles are generated here and served by a fake [TileFetcher].
 */
class ElevationTileTest {

    private val zoom = 12
    private val cache: File = createTempDirectory("elevation-tiles").toFile()

    @AfterTest
    fun cleanUp() {
        cache.deleteRecursively()
    }

    @Test
    fun `decoding reads the Terrarium encoding`() {
        val tile = TerrariumTile.decode(png { x, y -> if (x == 3 && y == 7) -12.5 else 1234.25 })

        assertEquals(-12.5, tile.pixel(3, 7), 1 / 256.0)
        assertEquals(1234.25, tile.pixel(0, 0), 1 / 256.0)
        assertEquals(0.0, TerrariumTile.elevation(128, 0, 0))
    }

    @Test
    fun `a pixel centre reads that pixel`() {
        val tile = TerrariumTile.decode(png { x, y -> x * 10.0 + y })
        val (latitude, longitude) = pointAt(tileX = 2200, tileY = 1300, pixelX = 40.5, pixelY = 20.5)

        val elevation = TerrariumGrid.elevationAt(latitude, longitude, zoom) { tile }

        assertNotNull(elevation)
        assertEquals(420.0, elevation, 0.01)
    }

    @Test
    fun `between pixel centres the elevation is interpolated bilinearly`() {
        val tile = TerrariumTile.decode(png { x, y -> x * 10.0 + y })
        val (latitude, longitude) = pointAt(tileX = 2200, tileY = 1300, pixelX = 41.0, pixelY = 20.75)

        val elevation = TerrariumGrid.elevationAt(latitude, longitude, zoom) { tile }

        // Halfway between columns 40 and 41, a quarter of the way from row 20 to 21.
        assertNotNull(elevation)
        assertEquals(425.25, elevation, 0.01)
    }

    @Test
    fun `at a tile edge the neighbouring tile is read too`() {
        val left = TerrariumTile.decode(png { _, _ -> 100.0 })
        val right = TerrariumTile.decode(png { _, _ -> 200.0 })
        val (latitude, longitude) = pointAt(tileX = 2201, tileY = 1300, pixelX = 0.0, pixelY = 100.5)

        assertEquals(
            setOf(TileKey(zoom, 2200, 1300), TileKey(zoom, 2201, 1300)),
            TerrariumGrid.tilesAround(latitude, longitude, zoom),
        )

        val elevation = TerrariumGrid.elevationAt(latitude, longitude, zoom) { key ->
            if (key.x == 2200) left else right
        }
        assertNotNull(elevation)
        assertEquals(150.0, elevation, 0.01)

        assertNull(TerrariumGrid.elevationAt(latitude, longitude, zoom) { key -> if (key.x == 2200) left else null })
    }

    @Test
    fun `every tile is downloaded once, also when asked for concurrently`() = runBlocking {
        val fetcher = CountingFetcher { _ -> delay(50); png { _, _ -> 321.0 } }
        val provider = provider(fetcher)
        val point = pointAt(tileX = 2200, tileY = 1300, pixelX = 128.5, pixelY = 128.5)

        val lookups = (1..8).map { async(Dispatchers.Default) { provider.lookupFor(listOf(point)) } }.awaitAll()

        assertEquals(1, fetcher.calls.get())
        for (lookup in lookups) assertEquals(321.0, lookup.elevationAt(point.first, point.second)!!, 0.01)
        assertTrue(cache.resolve("12/2200/1300.png").isFile)
        assertTrue(cache.resolve("12/2200").listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `a tile cached on disk is not downloaded again`() = runBlocking {
        val point = pointAt(tileX = 2200, tileY = 1300, pixelX = 128.5, pixelY = 128.5)
        provider(CountingFetcher { png { _, _ -> 321.0 } }).lookupFor(listOf(point))

        val restarted = CountingFetcher { error("must not be asked") }
        val elevation = provider(restarted).lookupFor(listOf(point)).elevationAt(point.first, point.second)

        assertEquals(0, restarted.calls.get())
        assertEquals(321.0, elevation!!, 0.01)
    }

    @Test
    fun `an unreadable cached tile is downloaded again`() = runBlocking {
        val point = pointAt(tileX = 2200, tileY = 1300, pixelX = 128.5, pixelY = 128.5)
        cache.resolve("12/2200").mkdirs()
        cache.resolve("12/2200/1300.png").writeText("truncated")

        val fetcher = CountingFetcher { png { _, _ -> 55.0 } }
        val elevation = provider(fetcher).lookupFor(listOf(point)).elevationAt(point.first, point.second)

        assertEquals(1, fetcher.calls.get())
        assertEquals(55.0, elevation!!, 0.01)
    }

    @Test
    fun `after a failed download, downloads pause and are retried later`() = runBlocking {
        val clock = FakeClock()
        var online = false
        val fetcher = CountingFetcher { if (online) png { _, _ -> 77.0 } else throw IOException("offline") }
        val provider = provider(fetcher, clock)
        val point = pointAt(tileX = 2200, tileY = 1300, pixelX = 128.5, pixelY = 128.5)
        val other = pointAt(tileX = 2300, tileY = 1300, pixelX = 128.5, pixelY = 128.5)

        assertNull(provider.lookupFor(listOf(point)).elevationAt(point.first, point.second))
        assertEquals(1, fetcher.calls.get())

        // Neither the same tile nor any other is asked for while downloads pause.
        online = true
        clock.now += 5.minutes
        assertNull(provider.lookupFor(listOf(point, other)).elevationAt(point.first, point.second))
        assertEquals(1, fetcher.calls.get())

        clock.now += 15.minutes
        assertEquals(77.0, provider.lookupFor(listOf(point)).elevationAt(point.first, point.second)!!, 0.01)
        assertEquals(2, fetcher.calls.get())
    }

    @Test
    fun `a tile the source does not have is not asked for on every lookup`() = runBlocking {
        val fetcher = CountingFetcher { null }
        val provider = provider(fetcher)
        val point = pointAt(tileX = 2200, tileY = 1300, pixelX = 128.5, pixelY = 128.5)

        repeat(3) { assertNull(provider.lookupFor(listOf(point)).elevationAt(point.first, point.second)) }

        assertEquals(1, fetcher.calls.get())
    }

    // --- helpers -----------------------------------------------------------------

    private fun provider(fetcher: TileFetcher, clock: Clock = Clock.System) = TerrainTileElevationProvider(
        cacheDirectory = cache,
        fetcher = fetcher,
        zoom = zoom,
        retryDelay = 15.minutes,
        clock = clock,
    )

    private class CountingFetcher(private val serve: suspend (TileKey) -> ByteArray?) : TileFetcher {
        val calls = AtomicInteger()

        override suspend fun fetch(key: TileKey): ByteArray? {
            calls.incrementAndGet()
            return serve(key)
        }
    }

    private class FakeClock(var now: Instant = Instant.parse("2026-09-29T08:00:00Z")) : Clock {
        override fun now(): Instant = now
    }

    /** A Terrarium PNG whose pixel in column x and row y holds `elevation(x, y)`. */
    private fun png(elevation: (Int, Int) -> Double): ByteArray {
        val image = BufferedImage(TerrariumTile.SIZE, TerrariumTile.SIZE, BufferedImage.TYPE_INT_RGB)

        for (y in 0 until TerrariumTile.SIZE) for (x in 0 until TerrariumTile.SIZE) {
            val value = elevation(x, y) + 32768
            val whole = floor(value).toInt()
            val red = whole / 256
            val green = whole % 256
            val blue = ((value - whole) * 256).toInt()
            image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
        }

        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Latitude and longitude of a position within a tile, in pixels from its top left corner. */
    private fun pointAt(tileX: Int, tileY: Int, pixelX: Double, pixelY: Double): Pair<Double, Double> {
        val worldPixels = (1 shl zoom) * TerrariumTile.SIZE.toDouble()
        val x = tileX * TerrariumTile.SIZE + pixelX
        val y = tileY * TerrariumTile.SIZE + pixelY

        val longitude = x / worldPixels * 360 - 180
        val latitude = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / worldPixels))))
        return latitude to longitude
    }
}
