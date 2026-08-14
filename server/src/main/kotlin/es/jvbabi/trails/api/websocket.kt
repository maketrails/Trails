package es.jvbabi.trails.api

import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

fun Application.installWebsocket() {
    install(WebSockets) {
        /*
         * A connection that dies without a close frame — flight mode, a lost network —
         * is only noticed through the ping, so these two bound how long a device is
         * still shown online after it is gone: at worst [pingPeriod] + [timeout].
         *
         * Deliberately not the same number. Asking often is cheap and shortens the
         * window; being impatient is not, because a pong that is merely slow on a bad
         * mobile link would close a working connection and make the device flicker
         * offline. So: ask every 5s, allow 10s to answer.
         */
        pingPeriod = 5.seconds
        timeout = 10.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false

        contentConverter = KotlinxWebsocketSerializationConverter(jsonInstance)
    }
}