package su.myt.home.ui

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import su.myt.home.model.App
import su.myt.home.model.AppGroup
import su.myt.home.pinned.PinnedAppsManager
import kotlin.math.abs

@Stable
internal class HomeDragState(
    private val density: Density,
    private val scope: CoroutineScope,
    private val getItems: () -> List<AppGroup>,
    private val onMoveItem: (from: Int, to: Int) -> Boolean,
    private val onGroupItems: (from: Int, to: Int, slot: Int) -> Boolean,
    private val onCanGroup: (from: Int, to: Int) -> Boolean,
    private val onExtractToPosition: (group: Int, app: Int, target: Int) -> Unit,
    private val onExtractToGroup: (group: Int, app: Int, target: Int, slot: Int) -> Unit,
    private val onReorderInGroup: (group: Int, app: Int, slot: Int) -> Unit,
    private val onDragActiveChange: (Boolean) -> Unit
) {
    var listRootX by mutableFloatStateOf(0f)
    var listWidth by mutableFloatStateOf(0f)
    var boxRoot by mutableStateOf(Offset.Zero)
    var drag by mutableStateOf<DragOverlay?>(null)
    var hoverGroup by mutableIntStateOf(-1)
    var hoverSlot by mutableIntStateOf(0)
    var insertRow by mutableIntStateOf(-1)
    var hoverCandidate by mutableIntStateOf(-1)
    var dwellEpoch by mutableIntStateOf(0)
    var dwellAnchorY by mutableFloatStateOf(0f)
    var settling by mutableStateOf(false)
    var revealDrop by mutableStateOf(false)
    var settleCompact by mutableStateOf(false)
    var floatX by mutableFloatStateOf(0f)
    var floatY by mutableFloatStateOf(0f)
    var floatW by mutableFloatStateOf(0f)
    var floatElev by mutableFloatStateOf(6f)
    val dragActive = mutableStateOf(false)
    val rowBounds = mutableMapOf<Int, RowBounds>()
    var dragLayout by mutableStateOf<Map<Int, RowBounds>>(emptyMap())
    var gapBounds by mutableStateOf<RowBounds?>(null)
    private var lastDragLog by mutableStateOf("")

    private val horizontalPadPx: Float
        get() = with(density) { ListHorizontalPadding.toPx() }
    val groupSpacingPx: Float
        get() = with(density) { GroupSpacing.toPx() }
    val rowHeightPx: Float
        get() = with(density) { RowHeight.toPx() }
    val rowLeft: Float
        get() = listRootX + horizontalPadPx
    val rowWidth: Float
        get() = (listWidth - 2f * horizontalPadPx).coerceAtLeast(0f)

    fun hitRows(): Map<Int, RowBounds> = dragLayout.ifEmpty { rowBounds.toMap() }

    fun pointerRootX(overlay: DragOverlay): Float = overlay.x + overlay.grabX
    fun pointerRootY(overlay: DragOverlay): Float = overlay.y + overlay.grabY

    fun remainingCount(groupIndex: Int, overlay: DragOverlay): Int {
        val items = getItems()
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
        val items = getItems()
        return when {
            index == from -> extracting
            extracting -> {
                val toSize = items.getOrNull(index)?.apps?.size
                toSize != null && toSize < PinnedAppsManager.MAX_GROUP_SIZE
            }
            else -> onCanGroup(from, index)
        }
    }

    fun onDragStart(
        appIndex: Int,
        dragged: App,
        root: Offset,
        width: Float,
        grab: Offset,
        isGroup: Boolean,
        index: Int
    ) {
        dragActive.value = true
        onDragActiveChange(true)
        dragLayout = rowBounds.toMap()
        dwellAnchorY = root.y
        dwellEpoch++
        hoverCandidate = if (isGroup) index else -1
        if (isGroup) {
            insertRow = -1
            hoverGroup = index
            hoverSlot = appIndex
        } else {
            insertRow = index
            hoverGroup = -1
            hoverSlot = appIndex
        }
        drag = DragOverlay(
            dragged, index, appIndex, root.x, root.y, width, root.x, root.y, grab.x, grab.y
        )
    }

    fun onOverlayDrag(delta: Offset) {
        if (settling) return
        val current = drag ?: return
        val next = current.copy(x = current.x + delta.x, y = current.y + delta.y)
        drag = next
        val items = getItems()
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
        if (candidate != hoverCandidate) {
            hoverCandidate = candidate ?: -1
            dwellAnchorY = next.y
            dwellEpoch++
        }
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

    suspend fun processDwell() {
        val target = hoverCandidate
        val overlay = drag
        if (overlay == null || target < 0) return
        delay(GroupDwellMs)
        if (hoverCandidate != target) return
        val current = drag ?: return
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
        val items = getItems()
        if (hoverGroup >= 0) {
            val n = items.getOrNull(hoverGroup)?.apps?.size ?: 1
            val count = if (overlay.from == hoverGroup) n else n + 1
            val width = slotWidthPx(rowWidth, count.coerceAtLeast(1), groupSpacingPx)
            return DragVisual(width, inGroup = true)
        }
        return DragVisual(rowWidth, inGroup = false)
    }

    fun dropTarget(overlay: DragOverlay): Triple<Float, Float, Float> {
        val items = getItems()
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
        val dest = moveDestination(overlay.from, insertRow, items.size)
        val y = if (!extracting) {
            tops.getOrNull(dest) ?: rowBounds[overlay.from]?.top ?: overlay.y
        } else {
            gapBounds?.top ?: gapTop(insertRow, tops, extracting = true, stride) ?: overlay.y
        }
        return Triple(rowLeft, y, rowWidth)
    }

    private fun commitDrop(current: DragOverlay, hover: Int, rowSlot: Int, slot: Int) {
        val items = getItems()
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
        val extracting = getItems().getOrNull(current.from)?.isGroup == true
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
        scope.launch {
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
                commitDrop(current, hover, rowSlot, slot)
                committed = true
                // Render the real destination beneath the overlay before removing it.
                revealDrop = true
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

    fun isOverItem(pointerY: Float): Boolean {
        return rowBounds.values.any { pointerY in it.top..(it.top + it.height) }
    }
}

@Composable
internal fun rememberHomeDragState(
    items: List<AppGroup>,
    onMoveItem: (from: Int, to: Int) -> Boolean,
    onGroupItems: (from: Int, to: Int, slot: Int) -> Boolean,
    onCanGroup: (from: Int, to: Int) -> Boolean,
    onExtractToPosition: (group: Int, app: Int, target: Int) -> Unit,
    onExtractToGroup: (group: Int, app: Int, target: Int, slot: Int) -> Unit,
    onReorderInGroup: (group: Int, app: Int, slot: Int) -> Unit,
    onDragActiveChange: (Boolean) -> Unit
): HomeDragState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val currentItems = androidx.compose.runtime.rememberUpdatedState(items)
    val currentMoveItem = androidx.compose.runtime.rememberUpdatedState(onMoveItem)
    val currentGroupItems = androidx.compose.runtime.rememberUpdatedState(onGroupItems)
    val currentCanGroup = androidx.compose.runtime.rememberUpdatedState(onCanGroup)
    val currentExtractToPos = androidx.compose.runtime.rememberUpdatedState(onExtractToPosition)
    val currentExtractToGroup = androidx.compose.runtime.rememberUpdatedState(onExtractToGroup)
    val currentReorderInGroup = androidx.compose.runtime.rememberUpdatedState(onReorderInGroup)
    val currentDragActiveChange = androidx.compose.runtime.rememberUpdatedState(onDragActiveChange)

    val state = remember {
        HomeDragState(
            density = density,
            scope = scope,
            getItems = { currentItems.value },
            onMoveItem = { from, to -> currentMoveItem.value(from, to) },
            onGroupItems = { from, to, slot -> currentGroupItems.value(from, to, slot) },
            onCanGroup = { from, to -> currentCanGroup.value(from, to) },
            onExtractToPosition = { g, a, t -> currentExtractToPos.value(g, a, t) },
            onExtractToGroup = { g, a, t, s -> currentExtractToGroup.value(g, a, t, s) },
            onReorderInGroup = { g, a, s -> currentReorderInGroup.value(g, a, s) },
            onDragActiveChange = { currentDragActiveChange.value(it) }
        )
    }

    LaunchedEffect(state.hoverCandidate, state.dwellEpoch, state.drag != null) {
        state.processDwell()
    }

    return state
}
