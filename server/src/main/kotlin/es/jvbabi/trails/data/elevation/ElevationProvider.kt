package es.jvbabi.trails.data.elevation

import java.io.Closeable

/**
 * Terrain elevation at a point, answered from data that is already at hand — no I/O.
 *
 * This is what [es.jvbabi.trails.data.TrackPipeline] reads: the pipeline is pure and
 * synchronous, so everything a batch needs is fetched up front by an
 * [ElevationProvider] and handed over as a lookup.
 */
fun interface ElevationLookup {
    /** Elevation above sea level in metres, or null where nothing is known. */
    fun elevationAt(latitude: Double, longitude: Double): Double?

    companion object {
        /** Knows nothing: every track counts as flat, the behaviour without elevation data. */
        val NONE = ElevationLookup { _, _ -> null }
    }
}

/**
 * Source of terrain elevation for the trail optimizer.
 *
 * Implementations never throw from [lookupFor]: missing data — offline, a failed
 * download, a disabled source — shows up as null elevations, and the optimizer then
 * classifies the track as if it were flat.
 */
interface ElevationProvider : Closeable {

    /**
     * Makes the elevation around [points] (latitude to longitude) available, fetching
     * whatever is not cached yet, and returns a lookup that answers for them without
     * further I/O.
     */
    suspend fun lookupFor(points: List<Pair<Double, Double>>): ElevationLookup

    override fun close() {}

    /** Used when elevation is switched off in the configuration. */
    object Disabled : ElevationProvider {
        override suspend fun lookupFor(points: List<Pair<Double, Double>>) = ElevationLookup.NONE
    }
}
