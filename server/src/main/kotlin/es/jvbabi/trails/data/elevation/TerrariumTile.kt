package es.jvbabi.trails.data.elevation

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** One tile of the web mercator tile grid. */
data class TileKey(val zoom: Int, val x: Int, val y: Int)

/**
 * A decoded elevation tile in the Terrarium encoding of the AWS Terrain Tiles: a
 * 256×256 PNG whose pixels carry the elevation as `R * 256 + G + B / 256 - 32768`
 * metres. Pixel values describe the centre of their pixel.
 */
class TerrariumTile(private val elevations: FloatArray) {

    init {
        require(elevations.size == SIZE * SIZE) { "A tile has ${SIZE * SIZE} pixels, not ${elevations.size}" }
    }

    /** Elevation of the pixel in column [x] and row [y], counted from the top left. */
    fun pixel(x: Int, y: Int): Double = elevations[y * SIZE + x].toDouble()

    companion object {
        const val SIZE = 256

        /** Decodes a Terrarium PNG, throwing if [bytes] is not one. */
        fun decode(bytes: ByteArray): TerrariumTile {
            val image = ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw IllegalArgumentException("Not an image")
            require(image.width == SIZE && image.height == SIZE) {
                "Expected a ${SIZE}×$SIZE tile, got ${image.width}×${image.height}"
            }

            val elevations = FloatArray(SIZE * SIZE)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val rgb = image.getRGB(x, y)
                elevations[y * SIZE + x] = elevation(
                    red = (rgb shr 16) and 0xFF,
                    green = (rgb shr 8) and 0xFF,
                    blue = rgb and 0xFF,
                ).toFloat()
            }

            return TerrariumTile(elevations)
        }

        /** The Terrarium encoding, see [TerrariumTile]. */
        fun elevation(red: Int, green: Int, blue: Int): Double = red * 256.0 + green + blue / 256.0 - 32768
    }
}

/** Where a point falls on the tile grid and how to interpolate it, see [corners]. */
object TerrariumGrid {

    /** One of the four pixels around a point, with its bilinear weight. */
    data class Corner(val tile: TileKey, val x: Int, val y: Int, val weight: Double)

    /**
     * The four pixel centres around a point at [zoom], with their bilinear weights.
     * Near a tile edge they lie in up to four different tiles; the grid wraps around
     * the antimeridian and is clamped at the poles.
     */
    fun corners(latitude: Double, longitude: Double, zoom: Int): List<Corner> {
        val worldPixels = (1 shl zoom) * TerrariumTile.SIZE
        val latitudeRadians = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))

        // Shifted by half a pixel, so the integer part names the pixel centre top left of the point.
        val pixelX = (longitude + 180) / 360 * worldPixels - 0.5
        val pixelY = (1 - asinh(tan(latitudeRadians)) / PI) / 2 * worldPixels - 0.5

        val left = floor(pixelX).toInt()
        val top = floor(pixelY).toInt()
        val fractionX = pixelX - left
        val fractionY = pixelY - top

        fun corner(column: Int, row: Int, weight: Double): Corner {
            val x = Math.floorMod(column, worldPixels)
            val y = row.coerceIn(0, worldPixels - 1)
            return Corner(
                tile = TileKey(zoom, x / TerrariumTile.SIZE, y / TerrariumTile.SIZE),
                x = x % TerrariumTile.SIZE,
                y = y % TerrariumTile.SIZE,
                weight = weight,
            )
        }

        return listOf(
            corner(left, top, (1 - fractionX) * (1 - fractionY)),
            corner(left + 1, top, fractionX * (1 - fractionY)),
            corner(left, top + 1, (1 - fractionX) * fractionY),
            corner(left + 1, top + 1, fractionX * fractionY),
        )
    }

    /** The tiles a bilinear lookup at the point needs. */
    fun tilesAround(latitude: Double, longitude: Double, zoom: Int): Set<TileKey> =
        corners(latitude, longitude, zoom).mapTo(mutableSetOf()) { it.tile }

    /**
     * Bilinearly interpolated elevation at the point, or null if one of the tiles it
     * needs is not available from [tiles].
     */
    fun elevationAt(latitude: Double, longitude: Double, zoom: Int, tiles: (TileKey) -> TerrariumTile?): Double? =
        corners(latitude, longitude, zoom).sumOf { corner ->
            val tile = tiles(corner.tile) ?: return null
            corner.weight * tile.pixel(corner.x, corner.y)
        }

    /** Where web mercator ends. */
    private const val MAX_LATITUDE = 85.05112878
}
