package dev.pockethost.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.pockethost.model.ServiceState

private val Blue = Color(0xFF2F81F7)
private val BlueDark = Color(0xFF1F6FEB)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Color(0xFF58A6FF),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2230),
    onSurfaceVariant = Color(0xFF9AA7B4),
    outline = Color(0xFF2A313C),
    error = Color(0xFFF85149),
)

private val LightColors = lightColorScheme(
    primary = BlueDark,
    onPrimary = Color.White,
    secondary = Color(0xFF1F6FEB),
    background = Color(0xFFF3F5F8),
    onBackground = Color(0xFF15191E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15191E),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF5B6672),
    outline = Color(0xFFD6DDE6),
    error = Color(0xFFD1242F),
)

/**
 * Semantic colors for service states. Kept outside the M3 scheme so a running
 * service is unmistakably green and a failure unmistakably red in either theme.
 */
data class StatusPalette(
    val ok: Color,
    val warn: Color,
    val error: Color,
    val idle: Color,
) {
    fun colorFor(state: ServiceState): Color = when (state) {
        ServiceState.Running -> ok
        ServiceState.Starting, ServiceState.Stopping, ServiceState.Degraded -> warn
        ServiceState.Failed, ServiceState.MissingBinary -> error
        ServiceState.Stopped -> idle
    }
}

val DarkStatus = StatusPalette(
    ok = Color(0xFF3FB950),
    warn = Color(0xFFD29922),
    error = Color(0xFFF85149),
    idle = Color(0xFF6E7681),
)
val LightStatus = StatusPalette(
    ok = Color(0xFF1A7F37),
    warn = Color(0xFF9A6700),
    error = Color(0xFFCF222E),
    idle = Color(0xFF8C959F),
)

@Composable
fun statusPalette(): StatusPalette = if (isSystemInDarkTheme()) DarkStatus else LightStatus

@Composable
fun PocketHostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
