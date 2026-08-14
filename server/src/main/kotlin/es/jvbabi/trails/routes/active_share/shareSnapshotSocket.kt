package es.jvbabi.trails.routes.active_share

import es.jvbabi.trails.data.ReverseGeocoding
import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.TrackRepository
import es.jvbabi.trails.data.event.ActiveShareEvent
import io.ktor.serialization.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * Public, multiplexed ring-of-shares snapshot channel. A client opens **one
 * socket per homeserver** and subscribes to any number of active-share ids over
 * it; the server replies with each share's current snapshot and pushes updates as
 * the underlying device reports new locations/battery.
 *
 * Unauthenticated by design: the active-share id is itself the capability (an
 * unguessable UUID), so possessing it is the authorization — the same model the
 * app's share subscription uses.
 *
 * What it subscribes to is the *share's* stream, not the device's: the repository
 * has already decided what this redemption may reveal, so nothing here has to know
 * the share's settings.
 */
fun Route.shareSnapshotSocket() {
    val reverseGeocoding by inject<ReverseGeocoding>()
    val shareRepository by inject<ShareRepository>()
    val trackRepository by inject<TrackRepository>()

    webSocket {
        // One live-update collector job per subscribed active-share id.
        val subscriptions = mutableMapOf<Uuid, Job>()

        suspend fun pushSnapshot(activeShareId: Uuid) {
            val snapshot = buildShareSnapshot(shareRepository, trackRepository, reverseGeocoding, activeShareId)
            if (snapshot != null) {
                sendSerialized<ShareSocketServerMessage>(
                    ShareSocketServerMessage.Snapshot(activeShareId.toString(), snapshot)
                )
            } else {
                sendSerialized<ShareSocketServerMessage>(ShareSocketServerMessage.Gone(activeShareId.toString()))
            }
        }

        suspend fun subscribe(activeShareId: Uuid) {
            if (subscriptions.containsKey(activeShareId)) return

            // Send the current snapshot right away, then follow the share.
            pushSnapshot(activeShareId)

            subscriptions[activeShareId] = launch {
                shareRepository.activeShareEvents(activeShareId)
                    .onEach { event ->
                        when (event) {
                            // Rebuilt rather than assembled from the event: the
                            // snapshot carries the whole shared device, and the
                            // position is only part of it.
                            is ActiveShareEvent.SnapshotAdded -> pushSnapshot(activeShareId)
                            is ActiveShareEvent.Gone -> sendSerialized<ShareSocketServerMessage>(
                                ShareSocketServerMessage.Gone(activeShareId.toString())
                            )
                            // The client is told what a share reveals, not how it is
                            // configured; the next snapshot already reflects it.
                            is ActiveShareEvent.SettingsChanged -> {}
                        }
                    }
                    .collect()
            }
        }

        fun unsubscribe(activeShareId: Uuid) {
            subscriptions.remove(activeShareId)?.cancel()
        }

        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val message = converter!!.deserialize<ShareSocketClientMessage>(frame)
            when (message) {
                is ShareSocketClientMessage.Subscribe ->
                    message.activeShareIds.mapNotNull(Uuid::parseOrNull).forEach { subscribe(it) }
                is ShareSocketClientMessage.Unsubscribe ->
                    message.activeShareIds.mapNotNull(Uuid::parseOrNull).forEach { unsubscribe(it) }
            }
        }

        subscriptions.values.forEach { it.cancel() }
    }
}

@Serializable
sealed class ShareSocketClientMessage {
    @SerialName("subscribe")
    @Serializable
    data class Subscribe(
        @SerialName("active_share_ids") val activeShareIds: List<String>,
    ) : ShareSocketClientMessage()

    @SerialName("unsubscribe")
    @Serializable
    data class Unsubscribe(
        @SerialName("active_share_ids") val activeShareIds: List<String>,
    ) : ShareSocketClientMessage()
}

@Serializable
sealed class ShareSocketServerMessage {
    @SerialName("share.snapshot")
    @Serializable
    data class Snapshot(
        @SerialName("active_share_id") val activeShareId: String,
        @SerialName("snapshot") val snapshot: ShareSnapshotResponse,
    ) : ShareSocketServerMessage()

    @SerialName("share.gone")
    @Serializable
    data class Gone(
        @SerialName("active_share_id") val activeShareId: String,
    ) : ShareSocketServerMessage()
}
