@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package su.myt.home.ui

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import su.myt.home.R
import su.myt.home.model.App
import su.myt.home.model.AppGroup
import su.myt.home.pinned.PinnedAppsManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val RowHeight = 64.dp
private val RowShape = RoundedCornerShape(20.dp)
private val ListHorizontalPadding = 16.dp
private val GroupSpacing = 8.dp
private val SearchBarHeight = 56.dp
private val SearchBarMargin = 16.dp
private const val AnimStiffness = 3200f
private val SlotSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = AnimStiffness
)
private val ItemPlacementSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = AnimStiffness
)
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

private data class DragOverlay(
    val app: App,
    val from: Int,
    val appIndex: Int,
    val originX: Float,
    val originWidth: Float,
    val x: Float,
    val y: Float,
    val grabX: Float,
    val grabY: Float
)

private sealed class GroupVisual {
    data class Item(val appIndex: Int, val app: App) : GroupVisual()
    data object Gap : GroupVisual()
}

private fun GroupVisual.slotKey(): String = when (this) {
    GroupVisual.Gap -> "gap"
    is GroupVisual.Item -> app.id
}

private class SlotAnim(x: Float, width: Float) {
    val x = Animatable(x)
    val width = Animatable(width)
}

private fun groupVisuals(
    apps: List<App>,
    draggingAppIndex: Int,
    insertSlot: Int
): List<GroupVisual> {
    val others = if (draggingAppIndex in apps.indices) {
        apps.indices.filter { it != draggingAppIndex }
    } else {
        apps.indices.toList()
    }
    val gapAt = if (insertSlot >= 0) insertSlot.coerceIn(0, others.size) else -1
    return buildList {
        others.forEachIndexed { i, appIndex ->
            if (i == gapAt) add(GroupVisual.Gap)
            add(GroupVisual.Item(appIndex, apps[appIndex]))
        }
        if (gapAt == others.size) add(GroupVisual.Gap)
    }
}

private sealed class PinnedVisual {
    data class Group(val index: Int) : PinnedVisual()
    data object Gap : PinnedVisual()
}

private fun pinnedVisuals(
    count: Int,
    draggingFrom: Int,
    extracting: Boolean,
    insertRow: Int
): List<PinnedVisual> {
    val skip = if (draggingFrom >= 0 && !extracting) draggingFrom else -1
    val rows = (0 until count).filter { it != skip }
    val gapAt = if (insertRow >= 0) insertRow.coerceIn(0, rows.size) else -1
    return buildList {
        rows.forEachIndexed { i, idx ->
            if (i == gapAt) add(PinnedVisual.Gap)
            add(PinnedVisual.Group(idx))
        }
        if (gapAt == rows.size) add(PinnedVisual.Gap)
    }
}

private data class DragVisual(
    val width: Float,
    val inGroup: Boolean
)


@Composable
fun HomeScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    items: List<AppGroup>,
    loading: Boolean,
    isSearching: Boolean,
    isPinned: (App) -> Boolean,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?,
    onLaunch: (App) -> Unit,
    onTogglePin: (App) -> Unit,
    onMoveItem: (from: Int, to: Int) -> Boolean,
    onGroupItems: (from: Int, to: Int, slot: Int) -> Boolean,
    onCanGroup: (from: Int, to: Int) -> Boolean,
    onExtractToPosition: (group: Int, app: Int, target: Int) -> Unit,
    onExtractToGroup: (group: Int, app: Int, target: Int, slot: Int) -> Unit,
    onReorderInGroup: (group: Int, app: Int, slot: Int) -> Unit,
    onEmptyLongPress: () -> Unit,
    onDragActiveChange: (Boolean) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val settleScope = rememberCoroutineScope()
    var listRootX by remember { mutableFloatStateOf(0f) }
    var listWidth by remember { mutableFloatStateOf(0f) }
    var boxRoot by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableStateOf<DragOverlay?>(null) }
    var hoverGroup by remember { mutableIntStateOf(-1) }
    var hoverSlot by remember { mutableIntStateOf(0) }
    var insertRow by remember { mutableIntStateOf(-1) }
    var hoverCandidate by remember { mutableIntStateOf(-1) }
    var dwellEpoch by remember { mutableIntStateOf(0) }
    var dwellAnchorY by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var revealDrop by remember { mutableStateOf(false) }
    var settleCompact by remember { mutableStateOf(false) }
    var floatX by remember { mutableFloatStateOf(0f) }
    var floatY by remember { mutableFloatStateOf(0f) }
    var floatW by remember { mutableFloatStateOf(0f) }
    var floatElev by remember { mutableFloatStateOf(6f) }
    val dragActive = remember { mutableStateOf(false) }
    val rowBounds = remember { mutableMapOf<Int, RowBounds>() }
    var dragLayout by remember { mutableStateOf<Map<Int, RowBounds>>(emptyMap()) }
    var gapBounds by remember { mutableStateOf<RowBounds?>(null) }
    var lastDragLog by remember { mutableStateOf("") }

    fun showIme() {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(Unit) { showIme() }
    BackHandler {
        onQueryChange("")
        showIme()
    }
    LaunchedEffect(query) {
        if (items.isNotEmpty()) listState.scrollToItem(0)
    }

    val foreground = colorResource(R.color.launcher_foreground)
    val muted = colorResource(R.color.launcher_muted)
    val rowColor = colorResource(R.color.launcher_row)
    val inputColor = colorResource(R.color.launcher_input)
    val accent = colorResource(R.color.launcher_accent)

    fun hitRows(): Map<Int, RowBounds> = dragLayout.ifEmpty { rowBounds.toMap() }

    fun pointerRootX(overlay: DragOverlay): Float = overlay.x + overlay.grabX

    val horizontalPadPx = with(density) { ListHorizontalPadding.toPx() }
    val groupSpacingPx = with(density) { GroupSpacing.toPx() }
    val rowHeightPx = with(density) { RowHeight.toPx() }
    val rowLeft = listRootX + horizontalPadPx
    val rowWidth = (listWidth - 2f * horizontalPadPx).coerceAtLeast(0f)

    fun remainingCount(groupIndex: Int, overlay: DragOverlay): Int {
        val n = items.getOrNull(groupIndex)?.apps?.size ?: return 0
        return if (overlay.from == groupIndex) (n - 1).coerceAtLeast(0) else n
    }

    fun insertSlotAt(groupIndex: Int, pointerX: Float, overlay: DragOverlay): Int {
        return insertSlotAlongRow(
            pointerX = pointerX,
            rowLeft = rowLeft,
            rowWidth = rowWidth,
            remaining = remainingCount(groupIndex, overlay),
            spacing = groupSpacingPx
        )
    }

    fun canGroupAt(from: Int, extracting: Boolean, index: Int): Boolean {
        return when {
            index == from -> extracting
            extracting -> {
                val toSize = items.getOrNull(index)?.apps?.size
                toSize != null && toSize < PinnedAppsManager.MAX_GROUP_SIZE
            }
            else -> onCanGroup(from, index)
        }
    }

    fun onOverlayDrag(delta: Offset) {
        if (settling) return
        val current = drag ?: return
        val next = current.copy(x = current.x + delta.x, y = current.y + delta.y)
        drag = next
        if (abs(next.y - dwellAnchorY) > rowHeightPx * 0.18f) {
            dwellAnchorY = next.y
            dwellEpoch++
        }
        val extracting = items.getOrNull(current.from)?.isGroup == true
        val frozen = hitRows()
        val stride = rowStride(orderedTops(frozen), rowHeightPx, groupSpacingPx)
        val packed = packedRowBounds(frozen, current.from, extracting, stride)
        val hit = dragHit(
            overlayTop = next.y,
            packed = packed,
            rowHeight = rowHeightPx,
            currentGroup = if (hoverGroup >= 0) hoverGroup else hoverCandidate,
            canTarget = { canGroupAt(current.from, extracting, it) }
        )
        val candidate = hit.group
        hoverCandidate = candidate ?: -1
        val committedGroup = candidate != null && candidate == hoverGroup
        if (committedGroup) {
            hoverGroup = candidate
            hoverSlot = insertSlotAt(candidate, pointerRootX(next), next)
            insertRow = -1
        } else {
            hoverGroup = -1
            insertRow = hit.insert
        }
        val log = "y=${next.y.toInt()} from=${current.from} ext=$extracting cand=$candidate hover=$hoverGroup insert=$insertRow packed=" +
            packed.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value.top.toInt()}" }
        if (log != lastDragLog) {
            lastDragLog = log
            Log.d("HomeDrag", log)
        }
    }

    LaunchedEffect(hoverCandidate, dwellEpoch, drag != null) {
        val target = hoverCandidate
        val overlay = drag
        if (overlay == null || target < 0) return@LaunchedEffect
        delay(GroupDwellMs)
        if (hoverCandidate != target) return@LaunchedEffect
        val current = drag ?: return@LaunchedEffect
        hoverGroup = target
        hoverSlot = insertSlotAt(target, pointerRootX(current), current)
        insertRow = -1
        Log.d("HomeDrag", "dwell hover=$target insert=-1")
    }

    fun clampedX(x: Float, width: Float): Float {
        val maxX = (rowLeft + rowWidth - width).coerceAtLeast(rowLeft)
        return x.coerceIn(rowLeft, maxX)
    }

    fun dragVisual(overlay: DragOverlay): DragVisual {
        if (hoverGroup >= 0) {
            val n = items.getOrNull(hoverGroup)?.apps?.size ?: 1
            val count = if (overlay.from == hoverGroup) n else n + 1
            val width = slotWidthPx(rowWidth, count.coerceAtLeast(1), groupSpacingPx)
            return DragVisual(width, inGroup = true)
        }
        return DragVisual(rowWidth, inGroup = false)
    }

    fun dropTarget(overlay: DragOverlay): Triple<Float, Float, Float> {
        val extracting = items.getOrNull(overlay.from)?.isGroup == true
        val frozen = hitRows()
        val stride = rowStride(orderedTops(frozen), rowHeightPx, groupSpacingPx)
        val packed = packedRowBounds(frozen, overlay.from, extracting, stride)
        val tops = orderedTops(packed)
        if (hoverGroup >= 0) {
            val y = rowBounds[hoverGroup]?.top ?: packed[hoverGroup]?.top ?: overlay.y
            val n = items.getOrNull(hoverGroup)?.apps?.size ?: 1
            val count = if (overlay.from == hoverGroup) n else n + 1
            val width = slotWidthPx(rowWidth, count.coerceAtLeast(1), groupSpacingPx)
            val maxSlot = (count - 1).coerceAtLeast(0)
            val slot = hoverSlot.coerceIn(0, maxSlot)
            val x = rowLeft + slot * (width + groupSpacingPx)
            return Triple(x, y, width)
        }
        val y = gapTop(insertRow, tops, extracting, stride) ?: gapBounds?.top ?: overlay.y
        return Triple(rowLeft, y, rowWidth)
    }

    fun commitDrop(current: DragOverlay, hover: Int, rowSlot: Int, slot: Int) {
        val extracting = items.getOrNull(current.from)?.isGroup == true
        if (hover >= 0) {
            if (extracting && hover == current.from) {
                onReorderInGroup(current.from, current.appIndex, slot)
                return
            }
            if (extracting) {
                onExtractToGroup(current.from, current.appIndex, hover, slot)
            } else {
                onGroupItems(current.from, hover, slot)
            }
            return
        }
        if (extracting) {
            onExtractToPosition(
                current.from,
                current.appIndex,
                extractDestination(rowSlot, current.from, items.size)
            )
            return
        }
        val dest = moveDestination(current.from, rowSlot, items.size)
        if (dest != current.from) onMoveItem(current.from, dest)
    }

    fun onOverlayDragEnd() {
        val current = drag ?: return
        if (settling) return
        val hover = hoverGroup
        val rowSlot = insertRow
        val slot = if (hover >= 0) {
            hoverSlot
        } else {
            insertSlotAt(current.from, pointerRootX(current), current)
        }
        val visual = dragVisual(current)
        val fromW = if (floatW > 0f) floatW else visual.width
        val fromX = if (floatW > 0f) floatX else clampedX(pointerRootX(current) - fromW / 2f, fromW)
        val fromY = if (floatW > 0f) floatY else current.y
        floatX = fromX
        floatY = fromY
        floatW = fromW
        floatElev = 6f
        settleCompact = visual.inGroup
        val (toX, toY, toW) = dropTarget(current)
        settling = true
        settleScope.launch {
            val xA = Animatable(fromX)
            val yA = Animatable(fromY)
            val wA = Animatable(fromW)
            val eA = Animatable(6f)
            var committed = false
            try {
                coroutineScope {
                    launch { xA.animateTo(toX, SlotSpring) { floatX = value } }
                    launch { yA.animateTo(toY, SlotSpring) { floatY = value } }
                    launch { wA.animateTo(toW, SlotSpring) { floatW = value } }
                    launch { eA.animateTo(0f, SlotSpring) { floatElev = value } }
                }
                revealDrop = true
                commitDrop(current, hover, rowSlot, slot)
                committed = true
                hoverGroup = -1
                insertRow = -1
                withFrameNanos { }
                withFrameNanos { }
            } finally {
                if (!committed) commitDrop(current, hover, rowSlot, slot)
                settling = false
                revealDrop = false
                drag = null
                hoverGroup = -1
                insertRow = -1
                hoverCandidate = -1
                dragLayout = emptyMap()
                dragActive.value = false
                onDragActiveChange(false)
            }
        }
    }

    val emptyLongPress = rememberUpdatedState(onEmptyLongPress)
    val boxOrigin = rememberUpdatedState(boxRoot)
    val searching = rememberUpdatedState(isSearching)
    val latestDragMove = rememberUpdatedState<(Offset) -> Unit>({ onOverlayDrag(it) })
    val latestDragEnd = rememberUpdatedState({ onOverlayDragEnd() })

    Box(
        Modifier
            .fillMaxSize()
            .displayCutoutPadding()
            .onGloballyPositioned { boxRoot = it.positionInRoot() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset ->
                        if (searching.value || dragActive.value) return@detectTapGestures
                        val pointerY = offset.y + boxOrigin.value.y
                        val overItem = rowBounds.values.any {
                            pointerY in it.top..(it.top + it.height)
                        }
                        if (!overItem) emptyLongPress.value()
                    }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!dragActive.value) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (!change.pressed) {
                            latestDragEnd.value()
                            change.consume()
                            continue
                        }
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            latestDragMove.value(delta)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                userScrollEnabled = drag == null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .onGloballyPositioned {
                        val pos = it.positionInRoot()
                        listRootX = pos.x
                        listWidth = it.size.width.toFloat()
                    }
                    .padding(horizontal = ListHorizontalPadding),
                contentPadding = PaddingValues(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
            ) {
                val extracting = items.getOrNull(drag?.from ?: -1)?.isGroup == true
                val visuals = pinnedVisuals(
                    count = items.size,
                    draggingFrom = if (revealDrop) -1 else (drag?.from ?: -1),
                    extracting = extracting && !revealDrop,
                    insertRow = when {
                        revealDrop -> -1
                        hoverGroup >= 0 -> -1
                        else -> insertRow
                    }
                )
                itemsIndexed(
                    visuals.asReversed(),
                    key = { _, visual ->
                        when (visual) {
                            PinnedVisual.Gap -> "insert-gap"
                            is PinnedVisual.Group ->
                                items[visual.index].apps.map { it.id }.sorted().joinToString(";")
                        }
                    }
                ) { _, visual ->
                    when (visual) {
                        PinnedVisual.Gap -> {
                            DisposableEffect(Unit) {
                                onDispose { gapBounds = null }
                            }
                            AnimatedInsertGap(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = snap(),
                                        fadeOutSpec = snap(),
                                        placementSpec = ItemPlacementSpring
                                    )
                                    .onGloballyPositioned {
                                        val pos = it.positionInRoot()
                                        gapBounds = RowBounds(pos.y, it.size.height.toFloat())
                                    },
                                rowColor = rowColor,
                                expandFromZero = false
                            )
                        }
                        is PinnedVisual.Group -> {
                            val index = visual.index
                            val group = items[index]
                            val grouping = hoverGroup == index
                            DisposableEffect(index) {
                                onDispose { rowBounds.remove(index) }
                            }
                            val placement = Modifier.animateItem(
                                fadeInSpec = snap(),
                                fadeOutSpec = snap(),
                                placementSpec = if (revealDrop) snap() else ItemPlacementSpring
                            )
                            val trackBounds = Modifier.onGloballyPositioned {
                                val pos = it.positionInRoot()
                                rowBounds[index] = RowBounds(pos.y, it.size.height.toFloat())
                            }
                            val app = group.apps.firstOrNull()
                            GroupRow(
                                group = group,
                                modifier = placement.then(trackBounds),
                                grouping = grouping && !revealDrop,
                                insertSlot = if (grouping && !revealDrop) hoverSlot else -1,
                                draggingAppIndex = if (!revealDrop && drag?.from == index) {
                                    drag?.appIndex ?: -1
                                } else {
                                    -1
                                },
                                rowWidthPx = rowWidth,
                                spacingPx = groupSpacingPx,
                                rowColor = rowColor,
                                foreground = foreground,
                                accent = accent,
                                showStar = isSearching && app != null && isPinned(app),
                                loadIcon = loadIcon,
                                onLaunch = onLaunch,
                                onLongClick = if (isSearching && app != null) {
                                    { onTogglePin(app) }
                                } else {
                                    null
                                },
                                dragEnabled = !isSearching && !grouping,
                                onDragStart = { appIndex, dragged, root, width, grab ->
                                    dragActive.value = true
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onDragActiveChange(true)
                                    dragLayout = rowBounds.toMap()
                                    dwellAnchorY = root.y
                                    dwellEpoch++
                                    hoverCandidate = if (group.isGroup) index else -1
                                    if (group.isGroup) {
                                        insertRow = -1
                                        hoverGroup = index
                                        hoverSlot = appIndex
                                    } else {
                                        insertRow = index
                                        hoverGroup = -1
                                        hoverSlot = appIndex
                                    }
                                    drag = DragOverlay(
                                        dragged, index, appIndex, root.x, width, root.x, root.y, grab.x, grab.y
                                    )
                                },
                                onDrag = { },
                                onDragEnd = { }
                            )
                        }
                    }
                }
            }

            SearchRow(
                query = query,
                loading = loading,
                foreground = foreground,
                muted = muted,
                inputColor = inputColor,
                accent = accent,
                focusRequester = focusRequester,
                onQueryChange = onQueryChange,
                onSearch = { items.lastOrNull()?.apps?.firstOrNull()?.let(onLaunch) },
                modifier = Modifier.padding(SearchBarMargin)
            )
        }

        drag?.let { overlay ->
            val visual = dragVisual(overlay)
            val animatedWidth by animateFloatAsState(visual.width, SlotSpring, label = "dragW")
            val liveX = clampedX(pointerRootX(overlay) - animatedWidth / 2f, animatedWidth)
            val liveY = overlay.y
            val drawX = if (settling) floatX else liveX
            val drawY = if (settling) floatY else liveY
            val drawW = if (settling) floatW else animatedWidth
            SideEffect {
                if (!settling) {
                    floatX = liveX
                    floatY = liveY
                    floatW = animatedWidth
                    floatElev = 6f
                }
            }
            Surface(
                modifier = Modifier
                    .zIndex(2f)
                    .offset {
                        IntOffset(
                            (drawX - boxRoot.x).roundToInt(),
                            (drawY - boxRoot.y).roundToInt()
                        )
                    }
                    .width(with(density) { drawW.toDp() })
                    .height(RowHeight),
                shape = RowShape,
                color = rowColor,
                shadowElevation = (if (settling) floatElev else 6f).dp
            ) {
                DragOverlayContent(
                    app = overlay.app,
                    compact = if (settling) settleCompact else visual.inGroup,
                    foreground = foreground,
                    loadIcon = loadIcon
                )
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    loading: Boolean,
    foreground: Color,
    muted: Color,
    inputColor: Color,
    accent: Color,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RowShape,
        color = inputColor,
        shadowElevation = 2.dp
    ) {
        Row(
            Modifier.height(SearchBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = { onQueryChange(it.take(100)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(color = foreground, fontSize = 18.sp),
                singleLine = true,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(if (loading) R.string.loading_apps else R.string.search_hint),
                                color = muted,
                                fontSize = 18.sp
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .padding(4.dp)
                        .size(48.dp)
                        .background(colorResource(R.color.launcher_row), CircleShape)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.clear_search),
                        tint = muted
                    )
                }
            }
        }
    }
}

@Composable
private fun DragOverlayContent(
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
private fun AppRowContent(
    app: App,
    showStar: Boolean,
    foreground: Color,
    accent: Color,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?
) {
    val description = if (showStar) {
        stringResource(R.string.pinned_description, app.badgedLabel)
    } else {
        app.badgedLabel
    }
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app, loadIcon)
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
        if (showStar) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = accent, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun GroupRow(
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
                    zIndex = if (visual is GroupVisual.Gap) 0f else 1f
                ) { widthPx ->
                    when (visual) {
                        is GroupVisual.Gap -> PlaceholderSlot(
                            Modifier.width(with(density) { widthPx.toDp() }),
                            rowColor
                        )
                        is GroupVisual.Item -> GroupAppSlot(
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

@Composable
private fun AnimatedSlot(
    anim: SlotAnim,
    targetX: Float,
    targetWidth: Float,
    zIndex: Float,
    content: @Composable (widthPx: Float) -> Unit
) {
    LaunchedEffect(targetX) { anim.x.animateTo(targetX, SlotSpring) }
    LaunchedEffect(targetWidth) { anim.width.animateTo(targetWidth, SlotSpring) }
    Box(
        Modifier
            .zIndex(zIndex)
            .offset { IntOffset(anim.x.value.roundToInt(), 0) }
    ) {
        content(anim.width.value)
    }
}

@Composable
private fun AnimatedInsertGap(
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
private fun PlaceholderSlot(
    modifier: Modifier,
    rowColor: Color
) {
    Surface(
        modifier = modifier.height(RowHeight),
        shape = RowShape,
        color = rowColor.copy(alpha = 0.35f)
    ) {}
}

@Composable
private fun GroupAppSlot(
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
