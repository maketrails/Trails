package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.ActiveShare
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.trails.api.v1.entity.ActiveShare as ActiveShareEntity

/**
 * One redemption of a share. Its id is the capability a client holds — nothing is
 * known about *who* redeemed it, since the redeemer may live on another homeserver.
 */
data class ActiveShareModel(
    val id: Uuid,
    val shareId: Uuid,
    val createdAt: Instant,
)

/** Must be called inside a transaction. */
fun ActiveShare.toModel() = ActiveShareModel(
    id = id.value,
    shareId = share.id.value,
    createdAt = createdAt,
)

/** The redemption as the API hands it out. */
fun ActiveShareModel.toApi() = ActiveShareEntity(
    id = id,
    shareId = shareId,
)
