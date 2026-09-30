package es.jvbabi.trails.tools

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import database.DataSnapshot
import database.DataSnapshots
import es.jvbabi.trails.data.TrackPipeline
import es.jvbabi.trails.data.TrackPipeline.Position
import es.jvbabi.trails.data.TrailOptimizer
import es.jvbabi.trails.database.Device
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Renders the speed of a device over time as a self-contained HTML chart, coloured by
 * the movement mode the server logs.
 *
 * Reads the raw positions straight from the server's SQLite database and runs them
 * through [TrackPipeline] in batches of [TrailOptimizer.BATCH_SIZE] — exactly what a
 * full re-optimization on the server does — so the chart shows the optimized track
 * and its classification as the current code produces them, whatever is stored.
 *
 * ```
 * ./gradlew :server:speedChart --args="--device <uuid> --from 2026-09-29T00:00"
 * ```
 *
 * Paths are relative to the repository root; charts go to the git-ignored
 * `speed-charts/` by default. Times are local, like the ones the chart shows. See the
 * README next to the `tools` source set.
 */
fun main(args: Array<String>) = SpeedChartCommand().main(args)

private class SpeedChartCommand : CliktCommand("speed-chart") {
    val device by option("--device", help = "Device UUID").required()
    val databaseFile by option("--db", help = "SQLite database of the server")
        .file(mustExist = true, canBeDir = false)
        .default(java.io.File("server/data/database.db"))
    val from by option("--from", help = "Only show positions recorded at or after this local time, e.g. 2026-09-29T00:00")
    val to by option("--to", help = "Only show positions recorded before this local time")
    val out by option("--out", help = "HTML file to write").file(canBeDir = false)
        .default(java.io.File("speed-charts/speed.html"))

    override fun run() {
        val deviceId = Uuid.parse(device)
        val database = Database.connect("jdbc:sqlite:${databaseFile.absolutePath}")

        val (name, raw) = transaction(db = database) {
            val name = Device.findById(deviceId)?.displayName
                ?: throw IllegalArgumentException("Unknown device $device")

            val raw = DataSnapshot
                .find { (DataSnapshots.device eq deviceId) and (DataSnapshots.isRaw eq true) }
                .orderBy(DataSnapshots.createdAt to SortOrder.ASC)
                .map { snapshot ->
                    Position(
                        timestamp = snapshot.createdAt,
                        latitude = snapshot.latitude,
                        longitude = snapshot.longitude,
                        accuracy = snapshot.locationAccuracy,
                        bearing = snapshot.bearing,
                        bearingAccuracy = snapshot.bearingAccuracy,
                        batteryLevel = snapshot.batteryLevel,
                        batteryCharging = snapshot.batteryCharging
                    )
                }

            name to raw
        }

        val segments = raw.chunked(TrailOptimizer.BATCH_SIZE).flatMap(TrackPipeline::process)
        val runs = segments.flatMap { TrackPipeline.runs(it.legs) }

        for (run in runs) {
            val reassigned = run.count { it.mode != it.measuredMode }
            echo(
                "${run.first().mode.name.padEnd(8)} ${run.first().start} – ${run.last().end}  " +
                        "${"%6.2f".format(run.sumOf { it.distanceMeters } / 1000)} km in ${run.size} legs" +
                        if (reassigned > 0) ", $reassigned reassigned" else ""
            )
        }

        val start = from?.let(::localInstant)
        val end = to?.let(::localInstant)
        val points = chartPoints(segments.flatMap { it.positions }, runs)
            .filter { point -> (start == null || point.time >= start) && (end == null || point.time < end) }

        if (points.isEmpty()) {
            echo("No consecutive positions to compute a speed from", err = true)
            return
        }

        val title = "$name — speed, optimized track".escapeHtml()
        val data = points.joinToString(",", "[", "]") { point ->
            "[${point.time.toEpochMilliseconds()},${"%.2f".format(java.util.Locale.ROOT, point.speedKmh)}," +
                    "${point.newSegment},${point.mode?.let { "\"${it.name.lowercase()}\"" } ?: "null"}]"
        }

        val template = SpeedChartCommand::class.java.getResource("/speed-chart.html")!!.readText()
        out.absoluteFile.parentFile.mkdirs()
        out.writeText(template.replace("__TITLE__", title).replace("__DATA__", data))

        echo("${points.size} speeds from ${raw.size} raw positions written to ${out.path}")
    }

    /** One step of the track: the speed from the previous position to this one. */
    private data class ChartPoint(
        val time: Instant,
        val speedKmh: Double,
        val newSegment: Boolean,
        val mode: TrackPipeline.MovementMode?
    )

    /**
     * The steps along [track] with the mode of the run they belong to. A run owns
     * everything from its first to its last position — the stops in between included —
     * so only the steps between two runs stay without a mode.
     */
    private fun chartPoints(track: List<Position>, runs: List<List<TrackPipeline.Leg>>): List<ChartPoint> {
        val spans = runs.map { run -> Triple(run.first().start, run.last().end, run.first().mode) }
        var span = 0
        var newSegment = true

        return track.zipWithNext().mapNotNull { (previous, current) ->
            val gap = (current.timestamp - previous.timestamp).inWholeMilliseconds / 1000.0
            if (gap > TrackPipeline.SEGMENT_GAP_SECONDS) {
                newSegment = true
                return@mapNotNull null
            }

            val speed = TrackPipeline.speedKmh(previous, current) ?: return@mapNotNull null

            while (span < spans.size && spans[span].second < current.timestamp) span++
            val mode = spans.getOrNull(span)
                ?.takeIf { (start, _, _) -> previous.timestamp >= start }
                ?.third

            ChartPoint(current.timestamp, speed, newSegment, mode).also { newSegment = false }
        }
    }

    private fun localInstant(value: String): Instant =
        LocalDateTime.parse(value).toInstant(TimeZone.currentSystemDefault())

    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
