package su.myt.home.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import su.myt.home.model.App
import kotlin.math.roundToInt

@Composable
internal fun DragOverlayContent(
    app: App,
    compact: Boolean,
    foreground: Color,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 0.dp else 16.dp)
            .semantics { contentDescription = app.badgedLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start
    ) {
        AppIcon(app, loadIcon)
        if (!compact) {
            Text(
                text = app.label,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                color = foreground,
                fontSize = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun DragFloatingOverlay(
    overlay: DragOverlay,
    dragState: HomeDragState,
    rowColor: Color,
    foreground: Color,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?
) {
    val density = LocalDensity.current
    val visual = dragState.dragVisual(overlay)
    val animatedWidth by animateFloatAsState(visual.width, SlotSpring, label = "dragW")

    val centeredTargetX = dragState.clampedX(dragState.pointerRootX(overlay) - animatedWidth / 2f, animatedWidth)

    val initialOffsetX = remember(overlay.from, overlay.appIndex) { overlay.originX - centeredTargetX }

    val pickupOffsetX = remember(overlay.from, overlay.appIndex) { Animatable(initialOffsetX) }
    val pickupElev = remember(overlay.from, overlay.appIndex) { Animatable(0f) }

    LaunchedEffect(overlay.from, overlay.appIndex) {
        pickupOffsetX.animateTo(0f, SlotSpring)
    }
    LaunchedEffect(overlay.from, overlay.appIndex) {
        pickupElev.animateTo(6f, SlotSpring)
    }

    val liveX = dragState.clampedX(centeredTargetX + pickupOffsetX.value, animatedWidth)
    val liveY = overlay.y
    val drawX = if (dragState.settling) dragState.floatX else liveX
    val drawY = if (dragState.settling) dragState.floatY else liveY
    val drawW = if (dragState.settling) dragState.floatW else animatedWidth
    val drawElev = if (dragState.settling) dragState.floatElev else pickupElev.value

    SideEffect {
        if (!dragState.settling) {
            dragState.floatX = liveX
            dragState.floatY = liveY
            dragState.floatW = animatedWidth
            dragState.floatElev = drawElev
        }
    }

    Surface(
        modifier = Modifier
            .zIndex(2f)
            .offset {
                IntOffset(
                    (drawX - dragState.boxRoot.x).roundToInt(),
                    (drawY - dragState.boxRoot.y).roundToInt()
                )
            }
            .width(with(density) { drawW.toDp() })
            .height(RowHeight),
        shape = RowShape,
        color = rowColor,
        shadowElevation = drawElev.dp
    ) {
        DragOverlayContent(
            app = overlay.app,
            compact = if (dragState.settling) dragState.settleCompact else visual.inGroup,
            foreground = foreground,
            loadIcon = loadIcon
        )
    }
}
