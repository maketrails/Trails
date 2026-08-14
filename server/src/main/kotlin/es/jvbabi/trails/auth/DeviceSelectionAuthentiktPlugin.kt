package es.jvbabi.trails.auth

import com.google.gson.annotations.SerializedName
import es.jvbabi.authentikt.core.AuthentiktInstance
import es.jvbabi.authentikt.core.session.Session
import es.jvbabi.authentikt.core.session.SessionKey
import es.jvbabi.authentikt.core.step.BaseState
import es.jvbabi.authentikt.core.step.plugins.BasePlugin
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.model.UserModel
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

class DeviceSelectionAuthentiktState(
    val deviceSelectionOptions: List<DeviceSelectionOption>,
    var selectedOption: UserSelection = UserSelection.Pending
): BaseState {
    override suspend fun isCompleted(): Boolean {
        return selectedOption !is UserSelection.Pending
    }

    override suspend fun createClientState(session: Session<*>): Map<String, Any?> {
        return mapOf(
            "options" to deviceSelectionOptions
        )
    }

    sealed class UserSelection {
        data object Pending : UserSelection()
        data class Selected(val device: DeviceSelectionOption) : UserSelection()
        data class NewDevice(val name: String) : UserSelection()
    }
}

class DeviceSelectionAuthentiktPlugin: BasePlugin<UserModel, DeviceSelectionAuthentiktState>(namespace = "trails/device-selection"), KoinComponent {

    private val deviceRepository by inject<DeviceRepository>()

    override suspend fun createState(session: Session<*>): DeviceSelectionAuthentiktState {
        val user = session.identifiedUser!!.user as UserModel

        val deviceModel = session.publicAttributes[deviceModelAttribute]!!
        val deviceManufacturer = session.publicAttributes[deviceManufacturerAttribute]!!

        val devices = deviceRepository
            .listOwnedByModel(user.id, manufacturer = deviceManufacturer, model = deviceModel)
            .map { device ->
                DeviceSelectionOption(
                    deviceId = device.id.toString(),
                    friendlyName = device.friendlyName,
                    displayName = device.displayName,
                    manufacturer = device.manufacturer,
                    model = device.model,
                    type = device.type.name,
                    createdAt = device.createdAt.epochSeconds
                )
            }

        return DeviceSelectionAuthentiktState(devices)
    }

    override fun installRoutes(
        inRoute: Route,
        authentiktInstance: AuthentiktInstance<UserModel>
    ) {
        with(inRoute) {
            post("/select") {
                val request = call.receive<DeviceSelectionRequest>()
                val session = call.attributes[SessionKey]
                val user = session.identifiedUser!!.user as UserModel
                val device = deviceRepository.getById(Uuid.parse(request.deviceId))!!
                if (device.ownerId != user.id) throw Exception("Device does not belong to user")
                if (device.isDeleted) throw Exception("Device is deleted")
                val state = session.authenticationSteps.last().second as DeviceSelectionAuthentiktState
                state.selectedOption = DeviceSelectionAuthentiktState.UserSelection.Selected(
                    device = state.deviceSelectionOptions.find { it.deviceId == request.deviceId }!!
                )
                session.nextStep()

                call.respond(buildMap { put("success", true) })
            }

            post("/new-device") {
                val request = call.receive<NewDeviceSelectionRequest>()
                val session = call.attributes[SessionKey]
                val user = session.identifiedUser!!.user as UserModel

                val isNameTaken = deviceRepository.existsOwnedWithDisplayName(user.id, request.name)

                if (isNameTaken) {
                    call.respond(buildMap { put("success", false); put("error", "name_already_exists") })
                    return@post
                }

                val state = session.authenticationSteps.last().second as DeviceSelectionAuthentiktState
                state.selectedOption = DeviceSelectionAuthentiktState.UserSelection.NewDevice(
                    name = request.name
                )

                session.nextStep()
                call.respond(buildMap { put("success", true) })
            }
        }
    }
}

data class DeviceSelectionOption(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("friendly_name") val friendlyName: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("model") val model: String,
    @SerializedName("type") val type: String,
    @SerializedName("created_at") val createdAt: Long,
)

@Serializable
data class DeviceSelectionRequest(
    @SerialName("device_id") val deviceId: String
)

@Serializable
data class NewDeviceSelectionRequest(
    @SerialName("name") val name: String,
)