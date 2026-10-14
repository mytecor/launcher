package su.myt.home.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity

@Composable
internal fun AnimatedInsertGap(
    modifier: Modifier,
    rowColor: Color,
    expandFromZero: Boolean
) {
    val density = LocalDensity.current
    val targetPx = with(density) { RowHeight.toPx() }
    val height = remember { Animatable(if (expandFromZero) 0f else targetPx) }
    LaunchedEffect(targetPx) { height.animateTo(targetPx, SlotSpring) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { height.value.toDp() }),
        shape = RowShape,
        color = rowColor.copy(alpha = 0.35f)
    ) {}
}

@Composable
internal fun PlaceholderSlot(
    modifier: Modifier,
    rowColor: Color
) {
    Surface(
        modifier = modifier.height(RowHeight),
        shape = RowShape,
        color = rowColor.copy(alpha = 0.35f)
    ) {}
}
