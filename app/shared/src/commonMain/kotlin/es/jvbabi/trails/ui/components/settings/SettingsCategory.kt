package es.jvbabi.trails.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Path
import com.phosphor.icons.regular.SignIn
import es.jvbabi.trails.ThemeWrapper

/**
 * Group of settings below a [title] header. The header lines up with the titles of items that
 * have an icon, like in the Android system settings.
 */
@Composable
fun SettingsCategory(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsCategoryHeader(title)
        content()
    }
}

@Composable
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SettingsDefaults.TextStartPadding,
                end = SettingsDefaults.HorizontalEndPadding,
                top = 24.dp,
            )
            .semantics { heading() },
    )
}

@Preview
@PreviewLightDark
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun SettingsCategoryPreview() {
    Column {
        SettingsCategory(title = "Tracking") {
            SettingsItem(
                title = "Minimum required movement",
                subtitle = "The minimum distance that has to be travelled before a new point is recorded.",
                icon = PhIcons.Regular.Path,
            )
        }
        SettingsCategory(title = "Account") {
            SettingsItem(title = "Sign in", icon = PhIcons.Regular.SignIn, onClick = {})
        }
    }
}
