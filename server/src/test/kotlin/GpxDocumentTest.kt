package es.jvbabi.trails

import es.jvbabi.trails.routes.devices.item.history.GpxPoint
import es.jvbabi.trails.routes.devices.item.history.attachmentHeader
import es.jvbabi.trails.routes.devices.item.history.gpxDocument
import es.jvbabi.trails.routes.devices.item.history.gpxFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What a reader downloads. The endpoint around it needs a session and a database;
 * the document, its name and the header that carries it do not, and those are the
 * parts that end up in somebody's file manager and in another program's parser.
 */
class GpxDocumentTest {

    private val start = Instant.parse("2026-09-01T12:00:00Z")
    private val end = Instant.parse("2026-09-08T12:30:00Z")

    private val track = listOf(
        GpxPoint(latitude = 52.5163, longitude = 13.3777, time = start),
        GpxPoint(latitude = 52.5200, longitude = 13.4050, time = end),
    )

    @Test
    fun `a track becomes one segment of dated points`() {
        val gpx = gpxDocument("Pixel 9", track)

        assertTrue(gpx.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertContains(gpx, """<gpx version="1.1" creator="Trails"""")
        assertContains(gpx, """<trkpt lat="52.5163" lon="13.3777"><time>2026-09-01T12:00:00Z</time></trkpt>""")
        assertContains(gpx, """<trkpt lat="52.52" lon="13.405"><time>2026-09-08T12:30:00Z</time></trkpt>""")
        assertEquals(1, gpx.split("<trkseg>").size - 1)
        assertEquals(2, gpx.split("<trkpt").size - 1)
        assertTrue(gpx.endsWith("</gpx>"))
    }

    @Test
    fun `the device names the document, escaped`() {
        val gpx = gpxDocument("Ben & Jerry's <phone>", track)

        assertContains(gpx, "<name>Ben &amp; Jerry&apos;s &lt;phone&gt;</name>")
        assertFalse(gpx.contains("<phone>"))
    }

    @Test
    fun `an empty window is still a document`() {
        val gpx = gpxDocument("Pixel 9", emptyList())

        assertContains(gpx, "<trkseg>")
        assertContains(gpx, "</gpx>")
        // No point, so there is no recording time to report either.
        assertFalse(gpx.contains("<time>"))
    }

    @Test
    fun `the file is named after the device and the window`() {
        assertEquals(
            "Pixel 9 2026-09-01 12-00-00Z to 2026-09-08 12-30-00Z.gpx",
            gpxFileName("Pixel 9", start, end),
        )
    }

    @Test
    fun `a name a file system would choke on is tidied up`() {
        val name = gpxFileName("""Ben/Jerry: "phone"?""", start, end)

        assertTrue(name.startsWith("Ben-Jerry- -phone--"), name)
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
    }

    @Test
    fun `the header offers the name twice, plain and encoded`() {
        val header = attachmentHeader(gpxFileName("Papas Büro-Handy", start, end))

        assertContains(header, """attachment; filename="Papas B_ro-Handy 2026-09-01 12-00-00Z to 2026-09-08 12-30-00Z.gpx"""")
        assertContains(
            header,
            "filename*=UTF-8''Papas%20B%C3%BCro-Handy%202026-09-01%2012-00-00Z%20to%202026-09-08%2012-30-00Z.gpx",
        )
    }
}
