package es.jvbabi.trails.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewWrapper
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.BellRinging
import com.phosphor.icons.regular.MapPin
import es.jvbabi.trails.ThemeWrapper

/**
 * [SettingsItem] with a trailing [Switch]. The whole row toggles the value, so the switch itself
 * doesn't handle input.
 */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

/**
 * [SettingsItem] with a trailing [Checkbox]. The whole row toggles the value, so the checkbox
 * itself doesn't handle input.
 */
@Composable
fun SettingsCheckboxItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        ),
        trailingContent = {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

@Preview
@PreviewLightDark
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun SettingsToggleItemsPreview() {
    var switchChecked by remember { mutableStateOf(true) }
    var checkboxChecked by remember { mutableStateOf(false) }

    Column {
        SettingsSwitchItem(
            title = "Share location",
            subtitle = "Lets others see where this device is.",
            icon = PhIcons.Regular.MapPin,
            checked = switchChecked,
            onCheckedChange = { switchChecked = it },
        )
        SettingsCheckboxItem(
            title = "Show home server in notification",
            subtitle = "Shows your home server (trails.example.com) in the tracking notification.",
            icon = PhIcons.Regular.BellRinging,
            checked = checkboxChecked,
            onCheckedChange = { checkboxChecked = it },
        )
    }
}
