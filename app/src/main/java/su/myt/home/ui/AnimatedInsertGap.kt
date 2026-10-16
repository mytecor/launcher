package su.myt.home.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun AnimatedInsertGap(
    modifier: Modifier,
    rowColor: Color,
    isClosing: Boolean = false,
    onCollapseComplete: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val targetPx = with(density) { RowHeight.toPx() }
    val height = remember { Animatable(0f) }
    val latestCollapse = rememberUpdatedState(onCollapseComplete)

    LaunchedEffect(isClosing, targetPx) {
        if (isClosing) {
            height.animateTo(0f, SlotSpring)
            latestCollapse.value?.invoke()
        } else {
            height.animateTo(targetPx, SlotSpring)
        }
    }

    val currentH = height.value
    val alphaFraction = if (targetPx > 0f) (currentH / targetPx).coerceIn(0f, 1f) else 1f

    if (currentH > 0.5f) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(with(density) { currentH.toDp() }),
            shape = RowShape,
            color = rowColor.copy(alpha = 0.35f * alphaFraction)
        ) {}
    } else {
        Box(modifier = modifier.fillMaxWidth().height(0.dp))
    }
}

@Composable
internal fun PlaceholderSlot(
    modifier: Modifier,
    rowColor: Color,
    alpha: Float = 0.35f,
    height: Dp = RowHeight
) {
    Surface(
        modifier = modifier.height(height),
        shape = RowShape,
        color = rowColor.copy(alpha = alpha.coerceIn(0f, 1f))
    ) {}
}
