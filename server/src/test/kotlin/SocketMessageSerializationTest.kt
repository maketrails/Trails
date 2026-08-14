package es.jvbabi.trails

import es.jvbabi.trails.api.jsonInstance
import es.jvbabi.trails.shared.dto.websocket.TrailsWebSocketAppMessage
import es.jvbabi.trails.shared.dto.websocket.TrailsWebSocketServerMessage
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every message of the socket protocol has to be registered with its base class,
 * which is what `@Serializable` on the subclass does. Miss it on one, and that
 * message throws the moment it is sent — at runtime, in production, and only for the
 * one feature that uses it.
 *
 * Checked against the sealed hierarchy rather than a list kept by hand, so a message
 * added tomorrow is covered without anybody remembering this file.
 */
class SocketMessageSerializationTest {

    @Test
    fun `every server message is registered`() =
        assertAllSubclassesRegistered(TrailsWebSocketServerMessage::class, serialNamesOfServerMessages())

    @Test
    fun `every app message is registered`() =
        assertAllSubclassesRegistered(TrailsWebSocketAppMessage::class, serialNamesOfAppMessages())

    private fun serialNamesOfServerMessages(): Set<String> =
        registeredSerialNames(serializer<TrailsWebSocketServerMessage>().descriptor)

    private fun serialNamesOfAppMessages(): Set<String> =
        registeredSerialNames(serializer<TrailsWebSocketAppMessage>().descriptor)

    /**
     * The serial names a sealed serializer knows about. A sealed descriptor carries
     * the type key and the value; the registered subclasses hang off the latter.
     */
    private fun registeredSerialNames(descriptor: kotlinx.serialization.descriptors.SerialDescriptor): Set<String> =
        (0 until descriptor.elementsCount)
            .map { descriptor.getElementDescriptor(it) }
            .flatMap { element ->
                (0 until element.elementsCount).map { element.getElementDescriptor(it).serialName }
            }
            .toSet()

    private fun assertAllSubclassesRegistered(base: KClass<*>, registered: Set<String>) {
        val subclasses = base.sealedSubclasses.filterNot { it.isAbstract }
        assertTrue(subclasses.isNotEmpty(), "No sealed subclasses found for ${base.simpleName}")

        // A registered name is the @SerialName; an unregistered subclass simply has no
        // entry, so comparing the counts is what catches the missing annotation.
        assertTrue(
            registered.size == subclasses.size,
            "${base.simpleName}: ${subclasses.size} subclasses but ${registered.size} registered " +
                    "(${registered.sorted()}) — one is missing @Serializable",
        )
    }

    @Test
    fun `a message survives the trip through the socket's json`() {
        val message: TrailsWebSocketServerMessage = TrailsWebSocketServerMessage.OnlineState(
            target = TrailsWebSocketServerMessage.Snapshot.Target.Device("00000000-0000-0000-0000-000000000000"),
            isOnline = false,
            since = 1_700_000_000_000,
        )

        val encoded = jsonInstance.encodeToString(message)
        assertTrue(encoded.contains("device.online_state"), "unexpected wire shape: $encoded")
        assertTrue(jsonInstance.decodeFromString<TrailsWebSocketServerMessage>(encoded) == message)
    }
}
