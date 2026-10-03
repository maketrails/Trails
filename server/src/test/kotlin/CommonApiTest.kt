package es.jvbabi.trails

import es.jvbabi.trails.api.API_PREFIX
import es.jvbabi.trails.api.installCommonApi
import es.jvbabi.trails.routes.EntityNotFoundException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommonApiTest {

    private fun ApplicationTestBuilder.withRoutes() {
        application {
            installCommonApi()
            routing {
                get("$API_PREFIX/ok") { call.respondText("fine") }
                get("$API_PREFIX/missing") { throw EntityNotFoundException("Device not found") }
                get("$API_PREFIX/bad") { throw BadRequestException("Malformed body") }
                get("$API_PREFIX/crash") { throw IllegalStateException("Boom") }
            }
        }
    }

    @Test
    fun `routes still answer normally`() = testApplication {
        withRoutes()
        val response = client.get("$API_PREFIX/ok")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("fine", response.bodyAsText())
    }

    @Test
    fun `entity not found becomes 404`() = testApplication {
        withRoutes()
        val response = client.get("$API_PREFIX/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue("Device not found" in response.bodyAsText())
    }

    @Test
    fun `ktor exceptions keep their status`() = testApplication {
        withRoutes()
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_PREFIX/bad").status)
    }

    @Test
    fun `other exceptions become 500`() = testApplication {
        withRoutes()
        assertEquals(HttpStatusCode.InternalServerError, client.get("$API_PREFIX/crash").status)
    }

    @Test
    fun `unknown routes under the prefix become 404`() = testApplication {
        withRoutes()
        assertEquals(HttpStatusCode.NotFound, client.get("$API_PREFIX/does-not-exist").status)
    }

    @Test
    fun `health check answers OK`() = testApplication {
        withRoutes()
        val response = client.get("$API_PREFIX/healthcheck")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `api info renders the service page`() = testApplication {
        withRoutes()
        val response = client.get("$API_PREFIX/ok?api_info")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("trails" in response.bodyAsText())
    }
}
