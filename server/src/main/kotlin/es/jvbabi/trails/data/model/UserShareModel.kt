package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.UserShare
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A redeemed share a user saved to their account, so it can be resolved again on the
 * next start. [activeShareId] lives on [homeserver], which is empty for a share of
 * this server.
 */
data class UserShareModel(
    val id: Uuid,
    val userId: Uuid,
    val activeShareId: Uuid,
    val homeserver: String,
    val createdAt: Instant,
) {
    /** Whether the share lives on this server and can be resolved locally. */
    val isLocal: Boolean get() = homeserver.isEmpty()
}

/** Must be called inside a transaction. */
fun UserShare.toModel() = UserShareModel(
    id = id.value,
    userId = user.id.value,
    activeShareId = shareId,
    homeserver = homeserver,
    createdAt = createdAt,
)
