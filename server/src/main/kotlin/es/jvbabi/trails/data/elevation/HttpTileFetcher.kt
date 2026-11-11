package es.jvbabi.trails.data.elevation

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.Closeable

/**
 * Fetches tiles over HTTP from [urlTemplate], in which `{z}`, `{x}` and `{y}` are
 * replaced by the tile coordinates.
 *
 * A 404 — or the 403 S3 answers for a key that does not exist — means the source has
 * no such tile; any other failure is thrown.
 */
class HttpTileFetcher(private val urlTemplate: String) : TileFetcher, Closeable {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    override suspend fun fetch(key: TileKey): ByteArray? {
        val url = urlTemplate
            .replace("{z}", key.zoom.toString())
            .replace("{x}", key.x.toString())
            .replace("{y}", key.y.toString())

        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, "TrailsApp/1.0 (trails)")
        }

        return when {
            response.status.isSuccess() -> response.bodyAsBytes()
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Forbidden -> null
            else -> error("$url answered ${response.status}")
        }
    }

    override fun close() = httpClient.close()
}
