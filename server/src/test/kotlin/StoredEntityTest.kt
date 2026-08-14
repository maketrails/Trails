package es.jvbabi.trails

import es.jvbabi.trails.data.stored
import es.jvbabi.trails.database.ActiveShare
import es.jvbabi.trails.database.ActiveShares
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.DeviceType
import es.jvbabi.trails.database.Devices
import es.jvbabi.trails.database.Share
import es.jvbabi.trails.database.Shares
import es.jvbabi.trails.database.User
import es.jvbabi.trails.database.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A column with a database-side default only exists once the row does. Reading it off
 * a freshly created entity throws, which is what made redeeming a share fail — the
 * repository maps what it just created, and the model carries `createdAt`.
 */
class StoredEntityTest {

    private fun withDatabase(block: () -> Unit) {
        Database.connect("jdbc:sqlite:file:test${System.nanoTime()}?mode=memory&cache=shared", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, Devices, Shares, ActiveShares)
            block()
        }
    }

    private fun newShare(): Share {
        val user = User.new {
            username = "test.user"
            email = "test@example.com"
            password = "irrelevant"
        }
        val device = Device.new {
            this.owner = user
            manufacturer = "Google"
            model = "panther"
            friendlyName = "Pixel 7"
            displayName = "Google Pixel 7"
            type = DeviceType.Phone
        }
        return Share.new {
            this.device = device
            shareName = "Share"
            locationHistorySeconds = 0
            shareBatteryState = false
            isLocked = false
            allowMultiuse = true
        }
    }

    @Test
    fun `a database default is not readable straight after creating the entity`() = withDatabase {
        val share = newShare()
        val activeShare = ActiveShare.new { this.share = share }

        assertFailsWith<IllegalStateException> { activeShare.createdAt }
    }

    @Test
    fun `stored makes the database default readable`() = withDatabase {
        val share = newShare()
        val activeShare = ActiveShare.new { this.share = share }.stored()

        assertTrue(activeShare.createdAt.toEpochMilliseconds() > 0)
    }
}
