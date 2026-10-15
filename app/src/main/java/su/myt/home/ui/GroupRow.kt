@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package su.myt.home.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import su.myt.home.model.App
import su.myt.home.model.AppGroup
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun GroupRow(
    group: AppGroup,
    modifier: Modifier,
    grouping: Boolean,
    insertSlot: Int,
    draggingAppIndex: Int,
    rowWidthPx: Float,
    spacingPx: Float,
    rowColor: Color,
    foreground: Color,
    accent: Color,
    showStar: Boolean,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?,
    onLaunch: (App) -> Unit,
    onLongClick: (() -> Unit)?,
    dragEnabled: Boolean,
    onDragStart: (appIndex: Int, app: App, origin: Offset, width: Float, grab: Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val density = LocalDensity.current
    val visuals = groupVisuals(group.apps, draggingAppIndex, insertSlot)
    val targetWidth = slotWidthPx(rowWidthPx, visuals.size.coerceAtLeast(1), spacingPx)
    val compact = visuals.size > 1
    val growFromFull = grouping && group.apps.size == 1 && draggingAppIndex < 0
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    val holdDrag = dragEnabled || draggingAppIndex >= 0
    val latestStart = rememberUpdatedState(onDragStart)
    val latestDrag = rememberUpdatedState(onDrag)
    val latestEnd = rememberUpdatedState(onDragEnd)
    val latestApps = rememberUpdatedState(group.apps)
    val latestRowWidth = rememberUpdatedState(rowWidthPx)
    val latestSpacing = rememberUpdatedState(spacingPx)
    val latestOrigin = rememberUpdatedState(rowOrigin)
    val slotAnims = remember { mutableMapOf<String, SlotAnim>() }
    val activeKeys = visuals.map { it.slotKey() }.toSet()
    SideEffect { slotAnims.keys.retainAll(activeKeys) }

    Box(
        modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clipToBounds()
            .onGloballyPositioned { rowOrigin = it.positionInRoot() }
            .then(
                if (holdDrag) {
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start ->
                                val apps = latestApps.value
                                val n = apps.size
                                if (n == 0) return@detectDragGesturesAfterLongPress
                                val w = slotWidthPx(latestRowWidth.value, n, latestSpacing.value)
                                val stride = w + latestSpacing.value
                                val idx = if (stride <= 0f) {
                                    0
                                } else {
                                    (start.x / stride).toInt().coerceIn(0, n - 1)
                                }
                                val origin = latestOrigin.value
                                latestStart.value(
                                    idx,
                                    apps[idx],
                                    Offset(origin.x + idx * stride, origin.y),
                                    w,
                                    Offset(start.x - idx * stride, start.y)
                                )
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                latestDrag.value(amount)
                            },
                            onDragEnd = { latestEnd.value() },
                            onDragCancel = {}
                        )
                    }
                } else Modifier
            )
    ) {
        visuals.forEachIndexed { index, visual ->
            val id = visual.slotKey()
            val x = index * (targetWidth + spacingPx)
            val initialWidth = when {
                visual is GroupVisual.Gap -> 0f
                growFromFull -> rowWidthPx
                else -> targetWidth
            }
            val initialX = if (growFromFull && visual is GroupVisual.Item) 0f else x
            val anim = slotAnims.getOrPut(id) { SlotAnim(initialX, initialWidth) }
            key(id) {
                AnimatedSlot(
                    anim = anim,
                    targetX = x,
                    targetWidth = targetWidth,
                    zIndex = if (visual is GroupVisual.Gap) 0f else 1f,
                    isItem = visual is GroupVisual.Item
                ) { widthPx ->
                    when (visual) {
                        is GroupVisual.Gap -> PlaceholderSlot(
                            Modifier.width(with(density) { widthPx.toDp() }),
                            rowColor
                        )
                        is GroupVisual.Item -> {
                            val isDragging = visual.appIndex == draggingAppIndex
                            if (isDragging) {
                                Box(
                                    Modifier
                                        .width(with(density) { widthPx.toDp() })
                                        .height(RowHeight)
                                )
                            } else {
                                GroupAppSlot(
                                    app = visual.app,
                                    modifier = Modifier.width(with(density) { widthPx.toDp() }),
                                    compact = compact,
                                    showStar = showStar && !compact,
                                    foreground = foreground,
                                    accent = accent,
                                    rowColor = rowColor,
                                    loadIcon = loadIcon,
                                    onClick = { onLaunch(visual.app) },
                                    onLongClick = if (compact) null else onLongClick
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnimatedSlot(
    anim: SlotAnim,
    targetX: Float,
    targetWidth: Float,
    zIndex: Float,
    isItem: Boolean,
    content: @Composable (widthPx: Float) -> Unit
) {
    LaunchedEffect(targetX, targetWidth) {
        if (isItem && anim.width.value <= 0f) {
            anim.x.snapTo(targetX)
            anim.width.snapTo(targetWidth)
        } else {
            coroutineScope {
                if (anim.x.value != targetX) {
                    launch { anim.x.animateTo(targetX, SlotSpring) }
                }
                if (anim.width.value != targetWidth) {
                    launch { anim.width.animateTo(targetWidth, SlotSpring) }
                }
            }
        }
    }
    Box(
        Modifier
            .zIndex(zIndex)
            .offset { IntOffset(anim.x.value.roundToInt(), 0) }
    ) {
        content(anim.width.value)
    }
}

@Composable
internal fun GroupAppSlot(
    app: App,
    modifier: Modifier,
    compact: Boolean,
    showStar: Boolean,
    foreground: Color,
    accent: Color,
    rowColor: Color,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Surface(
        modifier = modifier.height(RowHeight),
        shape = RowShape,
        color = rowColor
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        Modifier.clickable(onClick = onClick)
                    }
                ),
            contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart
        ) {
            if (compact) {
                AppIcon(app, loadIcon)
            } else {
                AppRowContent(app, showStar, foreground, accent, loadIcon)
            }
        }
    }
}
