package es.jvbabi.trails.data

import at.favre.lib.crypto.bcrypt.BCrypt
import es.jvbabi.trails.data.event.UserEvent
import es.jvbabi.trails.data.model.UserModel
import es.jvbabi.trails.data.model.toModel
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.User
import es.jvbabi.trails.database.Users
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * The accounts, and the per-account event stream.
 *
 * Credentials are checked here and nowhere else: the password hash and the TOTP
 * secret are never mapped into [UserModel], so no caller can hold them, log them
 * or compare them itself.
 */
class UserRepository : KoinComponent {
    private val db by inject<DatabaseManager>()

    private val events = mutableMapOf<Uuid, MutableSharedFlow<UserEvent>>()
    private val eventsMutex = Mutex()

    suspend fun getById(userId: Uuid): UserModel? =
        db.transaction { User.findById(userId)?.toModel() }

    /** Resolves the one field a user types to sign in — either of the two. */
    suspend fun findByEmailOrUsername(emailOrUsername: String): UserModel? =
        db.transaction {
            User.find { (Users.email eq emailOrUsername) or (Users.username eq emailOrUsername) }
                .firstOrNull()
                ?.toModel()
        }

    /**
     * The account with this e-mail address. Kept apart from
     * [findByEmailOrUsername] because an identity provider hands over an address,
     * and a username that happens to look like one must not match it.
     */
    suspend fun findByEmail(email: String): UserModel? =
        db.transaction { User.find { Users.email eq email }.firstOrNull()?.toModel() }

    /** Whether [password] is the account's. False for an unknown account. */
    suspend fun verifyPassword(userId: Uuid, password: String): Boolean {
        val hash = db.transaction { User.findById(userId)?.password } ?: return false
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified
    }

    /** The account's TOTP secret, or null when it has no second factor. */
    suspend fun otpSecret(userId: Uuid): String? =
        db.transaction { User.findById(userId)?.otp }

    /**
     * The account's event stream. One flow per user, created on first use and kept
     * for the lifetime of the process — subscribers come and go with connections.
     */
    suspend fun events(userId: Uuid): SharedFlow<UserEvent> = flowFor(userId)

    /**
     * Publishes an account-level event. Called by the repositories that own the
     * change (a device was renamed, a share was redeemed); nothing outside
     * [es.jvbabi.trails.data] has business announcing something it did not persist.
     */
    suspend fun publish(event: UserEvent) {
        flowFor(event.userId).emit(event)
    }

    private suspend fun flowFor(userId: Uuid): MutableSharedFlow<UserEvent> =
        eventsMutex.withLock { events.getOrPut(userId) { MutableSharedFlow(
            // Buffered and dropping, so publishing never waits on a subscriber. An
            // unbuffered flow suspends the *publisher* until every collector has taken
            // the value — one webapp socket busy re-sending its list (reverse geocoding
            // included) would hold up the device going offline for everyone else.
            //
            // Dropping is safe because these are signals, not a log: every consumer
            // re-reads the current state when it hears one, so a dropped event costs at
            // most one redundant re-send, and the next one still carries the truth.
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        ) } }
}
