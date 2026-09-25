package es.jvbabi.trails.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.BellRinging
import com.phosphor.icons.regular.Path
import com.phosphor.icons.regular.SignIn
import es.jvbabi.trails.ThemeWrapper

/**
 * Dimensions shared by all settings components, following the Material 3 list specs that the
 * Android system settings use as well.
 */
object SettingsDefaults {
    val HorizontalStartPadding = 16.dp
    val HorizontalEndPadding = 24.dp
    val VerticalPadding = 8.dp
    val MinHeight = 56.dp
    val IconSize = 24.dp
    val IconTextGap = 16.dp

    val TextStartPadding = HorizontalStartPadding

    /** Alpha applied to the content of disabled items, as specified by Material 3. */
    const val DisabledAlpha = 0.38f
}

/**
 * Base row of the settings screen: an optional leading [icon], a [title] with an optional
 * [subtitle], an optional [trailingContent] (switch, checkbox, …) and an optional [bottomContent]
 * rendered below the texts (slider, segmented buttons, …).
 *
 * The row is only clickable if [onClick] is set. Variants that toggle a value should pass their
 * own interaction via [modifier] instead, see [SettingsSwitchItem] and [SettingsCheckboxItem].
 */
@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = SettingsDefaults.MinHeight)
            .padding(
                start = SettingsDefaults.HorizontalStartPadding,
                end = SettingsDefaults.HorizontalEndPadding,
                top = SettingsDefaults.VerticalPadding,
                bottom = SettingsDefaults.VerticalPadding,
            ),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.alpha(if (enabled) 1f else SettingsDefaults.DisabledAlpha),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(SettingsDefaults.IconSize),
                )
                Spacer(Modifier.width(SettingsDefaults.IconTextGap))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailingContent != null) {
                Spacer(Modifier.width(16.dp))
                trailingContent()
            }
        }
        if (bottomContent != null) {
            Box(
                modifier = Modifier.padding(
                    start = if (icon != null) SettingsDefaults.IconSize + SettingsDefaults.IconTextGap else 0.dp,
                    top = 12.dp,
                ),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    bottomContent()
                }
            }
        }
    }
}

@Preview
@PreviewLightDark
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun SettingsItemPreview() {
    Column {
        SettingsItem(
            title = "Sign in",
            icon = PhIcons.Regular.SignIn,
            onClick = {},
        )
        SettingsItem(
            title = "Minimum required movement",
            subtitle = "The minimum distance that has to be travelled before a new point is recorded.",
            icon = PhIcons.Regular.Path,
            bottomContent = { Text("Custom content") },
        )
        SettingsItem(
            title = "Disabled item",
            subtitle = "This item can't be clicked.",
            icon = PhIcons.Regular.BellRinging,
            enabled = false,
            onClick = {},
        )
    }
}
