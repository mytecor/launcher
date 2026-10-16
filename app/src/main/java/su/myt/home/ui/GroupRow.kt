@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package su.myt.home.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.ui.input.pointer.PointerId
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
    onDragStart: (appIndex: Int, app: App, origin: Offset, grabX: Float, pointerId: PointerId) -> Unit
) {
    val density = LocalDensity.current

    var closingGapSlot by remember(group.apps.size) { mutableStateOf<Int?>(null) }

    if (insertSlot >= 0) {
        closingGapSlot = insertSlot
    }

    val isClosing = insertSlot < 0 && !grouping && closingGapSlot != null
    val activeSlot = if (insertSlot >= 0) insertSlot else closingGapSlot
    val visuals = groupVisuals(group.apps, draggingAppIndex, activeSlot ?: -1)

    val slotCount = if (isClosing) visuals.count { it is GroupVisual.Item } else visuals.size
    val targetWidth = slotWidthPx(rowWidthPx, slotCount.coerceAtLeast(1), spacingPx)
    val compact = slotCount > 1
    val growFromFull = grouping && group.apps.size == 1 && draggingAppIndex < 0
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    val holdDrag = dragEnabled || draggingAppIndex >= 0
    val latestStart = rememberUpdatedState(onDragStart)
    val latestDragEnabled = rememberUpdatedState(dragEnabled)
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
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            if (!latestDragEnabled.value) return@awaitEachGesture
                            val apps = latestApps.value
                            if (apps.isEmpty()) return@awaitEachGesture
                            val width = slotWidthPx(latestRowWidth.value, apps.size, latestSpacing.value)
                            val stride = width + latestSpacing.value
                            val index = if (stride <= 0f) 0 else (press.position.x / stride).toInt().coerceIn(apps.indices)
                            val origin = latestOrigin.value
                            press.consume()
                            latestStart.value(
                                index,
                                apps[index],
                                Offset(origin.x + index * stride, origin.y),
                                press.position.x - index * stride,
                                press.id
                            )
                        }
                    }
                } else Modifier
            )
    ) {
        val nonGapVisuals = visuals.filter { it !is GroupVisual.Gap }
        visuals.forEachIndexed { index, visual ->
            val id = visual.slotKey()
            val slotTargetWidth = if (visual is GroupVisual.Gap && isClosing) 0f else targetWidth
            val x = if (isClosing) {
                if (visual is GroupVisual.Gap) {
                    if (index >= nonGapVisuals.size) rowWidthPx else index * (targetWidth + spacingPx)
                } else {
                    val nonGapIndex = nonGapVisuals.indexOf(visual).coerceAtLeast(0)
                    nonGapIndex * (targetWidth + spacingPx)
                }
            } else {
                index * (targetWidth + spacingPx)
            }
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
                    targetWidth = slotTargetWidth,
                    zIndex = if (visual is GroupVisual.Gap) 0f else 1f,
                    isItem = visual is GroupVisual.Item,
                    onCollapseComplete = {
                        if (visual is GroupVisual.Gap && isClosing) {
                            closingGapSlot = null
                        }
                    }
                ) { widthPx ->
                    when (visual) {
                        is GroupVisual.Gap -> {
                            val fullW = slotWidthPx(rowWidthPx, visuals.size.coerceAtLeast(1), spacingPx)
                            val alphaFraction = if (fullW > 0f) (widthPx / fullW).coerceIn(0f, 1f) else 1f
                            PlaceholderSlot(
                                modifier = Modifier.width(with(density) { widthPx.toDp() }),
                                rowColor = rowColor,
                                alpha = 0.35f * alphaFraction
                            )
                        }
                        is GroupVisual.Item -> {
                            val isDragging = visual.appIndex == draggingAppIndex
                            if (isDragging) {
                                PlaceholderSlot(
                                    modifier = Modifier.width(with(density) { widthPx.toDp() }),
                                    rowColor = rowColor,
                                    alpha = 0.25f
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
    onCollapseComplete: (() -> Unit)? = null,
    content: @Composable (widthPx: Float) -> Unit
) {
    val latestCollapse = rememberUpdatedState(onCollapseComplete)
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
                    launch {
                        anim.width.animateTo(targetWidth, SlotSpring)
                        if (targetWidth <= 0f) {
                            latestCollapse.value?.invoke()
                        }
                    }
                } else if (targetWidth <= 0f) {
                    latestCollapse.value?.invoke()
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
            AppRowContent(app, showStar, foreground, accent, loadIcon, compact = compact)
        }
    }
}
