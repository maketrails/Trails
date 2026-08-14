package es.jvbabi.trails.auth

import es.jvbabi.authentikt.core.session.Session
import es.jvbabi.authentikt.core.step.plugins.BasePlugin
import es.jvbabi.trails.data.DeviceInformationRepository
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.model.UserModel
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * If the user has authenticated, we need to determine which device they are using. If it's a new device, we need to
 * create it and add it to the user's devices. If it may be already existing, we need to ask the user to select it.
 * Otherwise, we create a new one. If the user has already selected a device, we just return null.
 *
 * Linking a device is a write like any other, so it goes through
 * [DeviceRepository.create] — which is also what tells the user's other sessions
 * about it, without this having to remember to.
 *
 * @return The next authentication step we would want to go to get more device information or just null if we're done.
 */
suspend fun authSessionDeviceSelection(session: Session<UserModel>, user: UserModel): BasePlugin<UserModel, *>? {
    val deviceSelectionAuthentiktPlugin = GlobalContext.get().get<DeviceSelectionAuthentiktPlugin>()
    val deviceInformationRepository = GlobalContext.get().get<DeviceInformationRepository>()
    val deviceRepository = GlobalContext.get().get<DeviceRepository>()

    val deviceModel = session.publicAttributes[deviceModelAttribute]
    val deviceManufacturer = session.publicAttributes[deviceManufacturerAttribute]
    val authSessionDeviceId = session.attributes[authSessionSelectedDeviceIdAttribute]

    if (authSessionDeviceId != null) return null

    val existingDevices = if (deviceManufacturer != null && deviceModel != null) {
        deviceRepository.listOwnedByModel(user.id, manufacturer = deviceManufacturer, model = deviceModel)
    } else emptyList()

    val deviceInformation = if (deviceManufacturer != null && deviceModel != null) {
        deviceInformationRepository.getDeviceInformation(deviceManufacturer, deviceModel)
    } else null

    val manufacturer = deviceInformation?.manufacturer ?: deviceManufacturer ?: "Unknown"
    val friendlyName = deviceInformation?.friendlyName ?: "Unknown"

    if (session.has(deviceSelectionAuthentiktPlugin)) {
        val userSelection = (session.authenticationSteps.last().second as DeviceSelectionAuthentiktState).selectedOption
        when (userSelection) {
            is DeviceSelectionAuthentiktState.UserSelection.Pending -> {
                return deviceSelectionAuthentiktPlugin
            }

            is DeviceSelectionAuthentiktState.UserSelection.NewDevice -> {
                val device = deviceRepository.create(
                    ownerId = user.id,
                    manufacturer = manufacturer,
                    model = deviceInformation?.model ?: deviceModel ?: "Unknown",
                    friendlyName = friendlyName,
                    displayName = userSelection.name,
                ) ?: return deviceSelectionAuthentiktPlugin
                session.attributes[authSessionSelectedDeviceIdAttribute] = device.id
                return null
            }

            is DeviceSelectionAuthentiktState.UserSelection.Selected -> {
                session.attributes[authSessionSelectedDeviceIdAttribute] = Uuid.parse(userSelection.device.deviceId)
                return null
            }
        }
    }

    if (existingDevices.isEmpty()) {
        val device = deviceRepository.create(
            ownerId = user.id,
            manufacturer = manufacturer,
            model = deviceInformation?.model ?: deviceModel ?: "Unknown",
            friendlyName = friendlyName,
            displayName = "$manufacturer $friendlyName",
        ) ?: return deviceSelectionAuthentiktPlugin
        session.attributes[authSessionSelectedDeviceIdAttribute] = device.id

        return null
    }

    return deviceSelectionAuthentiktPlugin
}
