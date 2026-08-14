package es.jvbabi.trails.routes.devices.item

import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.model.DeviceModel
import es.jvbabi.trails.routes.EntityNotFoundException
import io.ktor.server.application.ApplicationCall
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

suspend fun ApplicationCall.getDevice(): DeviceModel {
    val deviceRepository by inject<DeviceRepository>()
    val deviceId = parameters["deviceId"]?.let(Uuid::parseOrNull) ?: throw EntityNotFoundException("Device not found")
    return deviceRepository.getById(deviceId) ?: throw EntityNotFoundException("Device not found")
}
