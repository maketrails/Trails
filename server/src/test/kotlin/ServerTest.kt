package es.jvbabi.trails

import es.jvbabi.trails.rootModule
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ServerTest {

    /*
     * Asserts that "/" answers 200, which the server has no route for — every route
     * lives under /api/v1. The test stopped compiling when rootModule gained its
     * parameter and has not run since, so nobody noticed. Made to compile and given
     * the configuration it needs, but left off until someone decides what "/" should
     * actually answer.
     */
    @Ignore("Asserts a root route the server does not have — see the comment above")
    @Test
    fun `test root endpoint`() = testApplication {
        // The application reads its configuration from the storage directory, so the
        // test brings its own — otherwise it fails while Koin builds ApplicationConfig,
        // long before any route is reached.
        val storage = createTempDirectory().toFile()
        storage.resolve("config.json").writeText("""{"base_url": "http://localhost:8080"}""")

        application {
            rootModule(ApplicationLaunchConfig(storageDirectory = storage))
        }
        // verify server root returns 200
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

}
