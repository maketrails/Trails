package es.jvbabi.trails.api

import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

fun Application.installWebsocket() {
    install(WebSockets) {
        /*
         * The transport-level fallback, and no more than that. What decides whether a
         * device counts as reachable is the app socket's own heartbeat (see
         * es.jvbabi.trails.routes.app.app), because a proxy in front of the server
         * answers these control frames itself and would keep a dead connection looking
         * healthy. Left slow on purpose: asking more often would cost a phone's radio
         * three times the wakeups for a signal that is not trusted with the decision.
         */
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false

        contentConverter = KotlinxWebsocketSerializationConverter(jsonInstance)
    }
}