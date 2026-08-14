package es.jvbabi.trails.page.devices.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.model.Snapshot
import es.jvbabi.trails.domain.model.User
import es.jvbabi.trails.domain.repository.BatteryState
import es.jvbabi.trails.domain.repository.Location
import es.jvbabi.trails.page.home.HomeState
import es.jvbabi.trails.ui.components.BatteryIcon
import es.jvbabi.trails.ui.components.BatteryOrientation
import es.jvbabi.trails.ui.components.DeviceImage
import es.jvbabi.trails.ui.components.desaturated
import es.jvbabi.trails.utils.rememberBitmapFromBytes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@Composable
fun DeviceCard(
    modifier: Modifier = Modifier,
    device: HomeState.HomeDevice,
    isThisDevice: Boolean,
    colors: DeviceCardDefaults.DeviceCardColors = DeviceCardDefaults.colors(),
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val bitmap = rememberBitmapFromBytes(device.image)
        // Null until the server has said anything about this device, which is not the
        // same as it being offline — an unknown device is drawn as usual.
        val isOnline = device.onlineState?.isOnline != false
        DeviceImage(
            bitmap = bitmap,
            modifier = Modifier.size(64.dp).desaturated(!isOnline),
        )

        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    Res.string.devices_card_owner,
                    device.device.displayName,
                    device.device.owner.username,
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    Res.string.devices_card_model,
                    device.device.friendlyName,
                    device.device.model,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    isThisDevice -> stringResource(Res.string.devices_card_this_device)
                    // While the device is unreachable, how long that has been is the
                    // more useful fact than when it last reported a position — the
                    // latter would keep counting up as if nothing had changed.
                    device.onlineState?.isOnline == false -> {
                        val since = device.onlineState.since
                        if (since == null) stringResource(Res.string.devices_card_offline)
                        else stringResource(
                            Res.string.devices_card_offline_for,
                            HumanReadable.duration(Clock.System.now() - since),
                        )
                    }
                    device.snapshot == null -> stringResource(Res.string.devices_card_never_seen)
                    else -> {
                        val instant = device.snapshot.time.toInstant(TimeZone.currentSystemDefault())
                        if (Clock.System.now().minus(instant) <= 1.minutes) {
                            stringResource(Res.string.devices_card_seen_just_now)
                        } else {
                            stringResource(
                                Res.string.devices_card_last_seen,
                                HumanReadable.timeAgo(instant),
                            )
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (device.snapshot?.batteryState != null) BatteryIcon(
            percentage = device.snapshot.batteryState.percentage,
            isCharging = device.snapshot.batteryState.isCharging,
            orientation = BatteryOrientation.Right,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .height(24.dp)
                .width(12.dp),
        )
    }
}

data object DeviceCardDefaults {
    data class DeviceCardColors(
        val background: Color,
    )

    @Composable
    fun colors(
        background: Color = MaterialTheme.colorScheme.surfaceVariant,
    ) = DeviceCardColors(
        background = background
    )
}


@Composable
@Preview
fun DeviceCardPreview() {
    val device = Device(
        id = Uuid.random(),
        manufacturer = "Google",
        model = "panther",
        friendlyName = "Pixel 7",
        displayName = "Google Pixel 7",
        owner = User(
            id = Uuid.random(),
            homeserver = "trailsdevelopment.jvbabi.es",
            username = "test.user"
        ),
        batteryState = Device.BatteryState.Shared(73, true),
    )
    DeviceCard(
        device = HomeState.HomeDevice(
            device = device,
            image = null,
            snapshot = Snapshot(
                id = Uuid.random(),
                device = device,
                time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                batteryState = BatteryState(73, true),
                location = Location(
                    latitude = 40.4168,
                    longitude = -3.7038,
                    bearing = 7f,
                    bearingAccuracy = null,
                    locationAccuracy = 4f,
                    time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                ),
                isSynced = false,
            )
        ),
        onClick = {},
        isThisDevice = true,
    )
}