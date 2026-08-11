package es.jvbabi.trails.database

import database.DataSnapshots
import es.jvbabi.trails.config.ApplicationConfig
import es.jvbabi.trails.config.ApplicationConfigFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DatabaseManager: KoinComponent {
    private val applicationConfig by inject<ApplicationConfig>()

    companion object {
        /** How long a write waits for the file to be free before giving up. */
        const val SQLITE_BUSY_TIMEOUT_MILLIS = 10_000
    }

    val database = when (val database = applicationConfig.database) {
        /*
         * SQLite serialises writers, and the defaults make that hurt: without a
         * write-ahead log a reader blocks a writer outright, and without a busy
         * timeout a writer that finds the file locked fails immediately with
         * SQLITE_BUSY instead of waiting its turn. An app catching up on
         * snapshots while the track optimizer rebuilds is exactly that
         * situation, so both are turned on here.
         */
        is ApplicationConfigFile.Database.Sqlite -> Database.connect(
            "jdbc:sqlite://${applicationConfig.storage.resolve(database.path).absolutePath}" +
                    "?journal_mode=WAL&busy_timeout=${SQLITE_BUSY_TIMEOUT_MILLIS}&synchronous=NORMAL"
        )
        is ApplicationConfigFile.Database.Postgresql -> Database.connect("jdbc:postgresql://${database.host}:${database.port}/${database.database}", driver = "org.postgresql.Driver", user = database.username, password = database.password)
    }

    init {
        transaction(db = database) {
            SchemaUtils.create(Users)
            SchemaUtils.create(Devices, Sessions, DeviceDeletions)
            SchemaUtils.create(DataSnapshots, Shares, ActiveShares)
            SchemaUtils.create(UserShares)
        }
    }

    suspend fun <T> transaction(block: () -> T): T {
        return withContext(Dispatchers.IO) {
            return@withContext transaction(db = this@DatabaseManager.database) {
                return@transaction block()
            }
        }
    }
}