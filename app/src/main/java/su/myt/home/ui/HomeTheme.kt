package su.myt.home.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import su.myt.home.R

internal val RowHeight = 64.dp
internal val RowShape = RoundedCornerShape(20.dp)
internal val ListHorizontalPadding = 16.dp
internal val GroupSpacing = 8.dp
internal val SearchBarHeight = 56.dp
internal val SearchBarMargin = 16.dp
internal const val AnimStiffness = 400f

internal val SlotSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = AnimStiffness
)

internal val ItemPlacementSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = AnimStiffness
)

internal val UiFadeSpec = androidx.compose.animation.core.tween<Float>(durationMillis = 240)
internal val UiScaleSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow
)
internal val UiDpSpec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.Dp>(durationMillis = 240)

@Composable
fun HomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = colorResource(R.color.launcher_accent),
            onPrimary = colorResource(R.color.launcher_background),
            surface = colorResource(R.color.launcher_row),
            onSurface = colorResource(R.color.launcher_foreground),
            surfaceVariant = colorResource(R.color.launcher_input),
            onSurfaceVariant = colorResource(R.color.launcher_muted)
        ),
        content = content
    )
}
