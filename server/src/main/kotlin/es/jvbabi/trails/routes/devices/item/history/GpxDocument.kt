package es.jvbabi.trails.routes.devices.item.history

import io.ktor.http.ContentDisposition
import java.net.URLEncoder
import kotlin.time.Instant

/**
 * Writing a track out as GPX: the document, the file name it is offered under, and
 * the header that carries that name.
 *
 * Kept apart from the endpoint and free of the domain model, so what a reader
 * downloads can be checked without a server, a database or a session.
 */

/** One position in a GPX track. */
internal data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    /** When the position was recorded. */
    val time: Instant,
)

/**
 * [track] as GPX 1.1, named after the device it came from.
 *
 * One segment: a recording gap is visible in the timestamps, and where to cut a
 * track into segments is a judgement no exporter should make for its reader.
 * Bearing and accuracy are left out — GPX 1.1 has no place for either on a track
 * point, and inventing an extension for them would only make the file harder to
 * open elsewhere.
 */
internal fun gpxDocument(name: String, track: List<GpxPoint>): String = buildString {
    val escaped = name.escapeXml()
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine(
        """<gpx version="1.1" creator="Trails" xmlns="http://www.topografix.com/GPX/1/1" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
            """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">"""
    )
    appendLine("  <metadata>")
    appendLine("    <name>$escaped</name>")
    track.firstOrNull()?.let { appendLine("    <time>${it.time}</time>") }
    appendLine("  </metadata>")
    appendLine("  <trk>")
    appendLine("    <name>$escaped</name>")
    appendLine("    <trkseg>")
    for (point in track) {
        appendLine("""      <trkpt lat="${point.latitude}" lon="${point.longitude}"><time>${point.time}</time></trkpt>""")
    }
    appendLine("    </trkseg>")
    appendLine("  </trk>")
    append("</gpx>")
}

/** `<device> <from> to <to>.gpx`, with everything a file system dislikes taken out. */
internal fun gpxFileName(deviceName: String, start: Instant, end: Instant): String {
    val name = deviceName.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()
    return "$name ${fileInstant(start)} to ${fileInstant(end)}.gpx"
}

/**
 * An instant as a file name may carry it: `2026-09-08 10-15-00Z`, always UTC.
 *
 * Seconds and all: a range is marked to the second, and two exports of the same
 * minute would otherwise arrive under one name.
 */
private fun fileInstant(instant: Instant): String =
    instant.toString().substringBefore('.').replace("T", " ").replace(":", "-")

/**
 * The `Content-Disposition` for [name], with both an ASCII fallback and the name as
 * it is meant to read: device names carry umlauts and spaces, which a plain
 * `filename` cannot hold (RFC 6266).
 */
internal fun attachmentHeader(name: String): String {
    val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
    return "${ContentDisposition.Attachment.disposition}; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

private fun String.escapeXml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
