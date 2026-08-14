package es.jvbabi.trails.data

import es.jvbabi.trails.data.model.AuthenticatedSessionModel
import es.jvbabi.trails.data.model.SessionModel
import es.jvbabi.trails.data.model.toModel
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.Devices
import es.jvbabi.trails.database.Session
import es.jvbabi.trails.database.Sessions
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/** The devices' sign-ins. Publishes nothing — a session is not something a UI shows. */
class SessionRepository : KoinComponent {
    private val db by inject<DatabaseManager>()

    /**
     * The session a bearer token stands for, together with its device and account,
     * or `null` when the token is unknown, invalidated, or not [ownerId]'s.
     *
     * The owner is part of the query rather than checked afterwards: a token that
     * belongs to somebody else must not authenticate, and answering `null` makes
     * that indistinguishable from an unknown token.
     */
    suspend fun findByToken(tokenHash: String, ownerId: Uuid): AuthenticatedSessionModel? =
        db.transaction {
            val session = Sessions
                .leftJoin(Devices, { Sessions.device }, { Devices.id })
                .selectAll()
                .where { Sessions.tokenHash eq tokenHash }
                .andWhere { Devices.owner eq ownerId }
                .andWhere { Sessions.invalidatedAt.isNull() }
                .singleOrNull()
                ?.let { Session.wrapRow(it) }
                ?: return@transaction null

            val device = session.device
            AuthenticatedSessionModel(
                user = device.owner.toModel(),
                device = device.toModel(),
                session = session.toModel(),
            )
        }

    /**
     * Records a new sign-in of [deviceId] under [tokenHash], or `null` when the
     * device is gone. Only the hash is stored — the token itself never is.
     */
    suspend fun create(deviceId: Uuid, tokenHash: String): SessionModel? =
        db.transaction {
            val device = Device.findById(deviceId) ?: return@transaction null
            Session.new {
                this.device = device
                this.tokenHash = tokenHash
            }.stored().toModel()
        }
}
