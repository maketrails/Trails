package es.jvbabi.trails

import es.jvbabi.trails.config.ApplicationConfig
import es.jvbabi.trails.data.GeocodedAddress
import es.jvbabi.trails.data.ReverseGeocoding
import es.jvbabi.trails.data.ReverseGeocodingRepository
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.ReverseGeocodings
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class ReverseGeocodingRepositoryTest {

    /** A provider that records what it was asked and answers with [answer]. */
    private class FakeReverseGeocoding : ReverseGeocoding {
        data class Call(val latitude: Double, val longitude: Double, val language: String)

        val calls = mutableListOf<Call>()
        var answer: (Call) -> GeocodedAddress? = { address(it.language) }

        override suspend fun reverseGeocode(latitude: Double, longitude: Double, language: String): GeocodedAddress? {
            val call = Call(latitude, longitude, language)
            calls += call
            return answer(call)
        }
    }

    private val provider = FakeReverseGeocoding()
    private lateinit var db: DatabaseManager
    private lateinit var repository: ReverseGeocodingRepository

    @BeforeTest
    fun setUp() {
        // ApplicationConfig reads its configuration from the storage directory and
        // defaults to an SQLite file inside it, so every test gets a fresh database.
        val storage = createTempDirectory().toFile()
        storage.resolve("config.json").writeText("""{"base_url": "http://localhost:20416"}""")

        startKoin {
            modules(module {
                single { ApplicationConfig(storageDirectory = storage.absolutePath) }
                single { DatabaseManager() }
                single<ReverseGeocoding> { provider }
                single { ReverseGeocodingRepository() }
            })
        }.koin.let {
            db = it.get()
            repository = it.get()
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `a miss asks the provider for the cell center and stores the answer`() = runBlocking {
        val address = repository.addressFor(52.399734377861, 13.1780245304108, "de")

        assertEquals(address("de"), address)
        assertEquals(1, provider.calls.size)
        assertEquals(52.3997, provider.calls.single().latitude, 1e-9)
        assertEquals(13.178, provider.calls.single().longitude, 1e-9)

        val row = db.transaction { ReverseGeocodings.selectAll().single() }
        assertEquals(523997, row[ReverseGeocodings.latitudeKey])
        assertEquals(131780, row[ReverseGeocodings.longitudeKey])
        assertEquals("de", row[ReverseGeocodings.language])
        assertEquals(true, row[ReverseGeocodings.found])
    }

    @Test
    fun `another position in the same cell is answered from the table`() = runBlocking {
        repository.addressFor(52.39973, 13.17802, "de")
        val second = repository.addressFor(52.39968, 13.17798, "de")

        assertEquals(address("de"), second)
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun `a neighbouring cell is looked up on its own`() = runBlocking {
        repository.addressFor(52.3997, 13.1780, "de")
        repository.addressFor(52.3998, 13.1780, "de")

        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `every language is cached separately`() = runBlocking {
        assertEquals(address("de"), repository.addressFor(52.3997, 13.1780, "de"))
        assertEquals(address("en"), repository.addressFor(52.3997, 13.1780, "en"))
        assertEquals(address("de"), repository.addressFor(52.3997, 13.1780, "de"))

        assertEquals(listOf("de", "en"), provider.calls.map { it.language })
    }

    @Test
    fun `no address is cached as an answer`() = runBlocking {
        provider.answer = { null }

        assertNull(repository.addressFor(0.0, 0.0, "en"))
        assertNull(repository.addressFor(0.0, 0.0, "en"))

        assertEquals(1, provider.calls.size)
        val row = db.transaction { ReverseGeocodings.selectAll().single() }
        assertEquals(false, row[ReverseGeocodings.found])
        assertNull(row[ReverseGeocodings.displayName])
    }

    @Test
    fun `a failing provider is not cached`() = runBlocking {
        provider.answer = { error("rate limited") }

        assertNull(repository.addressFor(52.3997, 13.1780, "de"))
        assertEquals(0L, db.transaction { ReverseGeocodings.selectAll().count() })

        provider.answer = { address(it.language) }
        assertEquals(address("de"), repository.addressFor(52.3997, 13.1780, "de"))
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `a stale entry is resolved again`() = runBlocking {
        repository.addressFor(52.3997, 13.1780, "de")
        makeStale()

        val refreshed = GeocodedAddress(null, null, null, "Potsdam", null, null, null)
        provider.answer = { refreshed }

        assertEquals(refreshed, repository.addressFor(52.3997, 13.1780, "de"))
        assertEquals(2, provider.calls.size)
        // The refreshed answer is stored and fresh again.
        assertEquals(refreshed, repository.addressFor(52.3997, 13.1780, "de"))
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `a stale entry is kept when the provider fails`() = runBlocking {
        repository.addressFor(52.3997, 13.1780, "de")
        makeStale()
        provider.answer = { error("rate limited") }

        assertEquals(address("de"), repository.addressFor(52.3997, 13.1780, "de"))
    }

    @Test
    fun `the language is picked from the supported ones`() {
        assertEquals("de", ReverseGeocodingRepository.languageFor(listOf("de-DE", "en")))
        assertEquals("en", ReverseGeocodingRepository.languageFor(listOf("EN_us")))
        assertEquals("de", ReverseGeocodingRepository.languageFor(listOf("fr-FR", "de")))
        assertEquals("en", ReverseGeocodingRepository.languageFor(listOf("fr")))
        assertEquals("en", ReverseGeocodingRepository.languageFor(emptyList()))
    }

    private suspend fun makeStale() {
        db.transaction {
            ReverseGeocodings.update {
                it[resolvedAt] = Clock.System.now() - ReverseGeocodingRepository.MAX_AGE - 1.days
            }
        }
    }

    private companion object {
        fun address(language: String) = GeocodedAddress(
            road = "Musterstraße",
            houseNumber = "12",
            postcode = "14467",
            city = "Potsdam",
            state = "Brandenburg",
            country = if (language == "de") "Deutschland" else "Germany",
            displayName = null,
        )
    }
}
