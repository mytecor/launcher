package su.myt.home.ui

import kotlin.math.abs
import kotlin.math.roundToInt

internal fun slotWidthPx(rowWidth: Float, count: Int, spacingPx: Float): Float {
    if (count <= 0) return rowWidth
    return ((rowWidth - spacingPx * (count - 1)) / count).coerceAtLeast(0f)
}

internal data class RowBounds(val top: Float, val height: Float)

internal fun orderedTops(bounds: Map<Int, RowBounds>): List<Float> =
    bounds.entries.sortedBy { it.key }.map { it.value.top }

internal fun rowStride(tops: List<Float>, height: Float, spacing: Float): Float {
    if (tops.size >= 2) {
        val span = tops.last() - tops.first()
        if (span > 0f) return span / (tops.size - 1)
    }
    return height + spacing
}

internal fun closestMoveSlot(overlayTop: Float, tops: List<Float>, stride: Float): Int {
    if (tops.isEmpty()) return 0
    var best = tops.size
    var bestDist = abs(overlayTop - (tops.last() + stride))
    for (i in tops.indices) {
        val dist = abs(overlayTop - tops[i])
        if (dist < bestDist) {
            bestDist = dist
            best = i
        }
    }
    return best
}

internal fun extractGapTop(slot: Int, lastTop: Float, count: Int, stride: Float): Float {
    val s = slot.coerceIn(0, count)
    return lastTop - (count - s) * stride
}

internal fun closestExtractSlot(overlayTop: Float, tops: List<Float>, stride: Float): Int {
    val n = tops.size
    if (n == 0) return 0
    val last = tops.last()
    var best = 0
    var bestDist = Float.MAX_VALUE
    for (s in 0..n) {
        val dist = abs(overlayTop - extractGapTop(s, last, n, stride))
        if (dist < bestDist) {
            bestDist = dist
            best = s
        }
    }
    return best
}

internal fun insertRowSlot(
    overlayTop: Float,
    tops: List<Float>,
    extracting: Boolean,
    stride: Float
): Int {
    if (tops.isEmpty()) return 0
    return if (extracting) {
        closestExtractSlot(overlayTop, tops, stride)
    } else {
        closestMoveSlot(overlayTop, tops, stride)
    }
}

internal fun moveDestination(from: Int, insertSlot: Int, count: Int): Int {
    if (count <= 0) return 0
    if (insertSlot < 0) return from.coerceIn(0, count - 1)
    val max = count - 1
    return when {
        insertSlot > from -> (insertSlot - 1).coerceIn(0, max)
        else -> insertSlot.coerceIn(0, max)
    }
}

internal fun extractDestination(insertSlot: Int, from: Int, count: Int): Int {
    if (insertSlot < 0) return (from + 1).coerceIn(0, count)
    return insertSlot.coerceIn(0, count)
}

internal const val GroupDwellMs = 700L

internal fun gapTop(
    slot: Int,
    tops: List<Float>,
    extracting: Boolean,
    stride: Float
): Float? {
    if (tops.isEmpty()) return null
    return if (extracting) {
        extractGapTop(slot, tops.last(), tops.size, stride)
    } else {
        if (slot >= tops.size) tops.last() + stride else tops.getOrNull(slot.coerceAtLeast(0))
    }
}

internal fun stickyInsertSlot(
    current: Int,
    overlayTop: Float,
    tops: List<Float>,
    extracting: Boolean,
    stride: Float,
    rowHeight: Float
): Int {
    if (!extracting && tops.isNotEmpty() && overlayTop >= tops.last() + rowHeight * 0.5f) {
        return tops.size
    }
    val raw = insertRowSlot(overlayTop, tops, extracting, stride)
    if (current < 0 || tops.isEmpty()) return raw
    val currentY = gapTop(current, tops, extracting, stride) ?: return raw
    val rawY = gapTop(raw, tops, extracting, stride) ?: return raw
    if (abs(overlayTop - rawY) + rowHeight * 0.18f < abs(overlayTop - currentY)) return raw
    return current
}

internal fun remainingSlotOf(itemIndex: Int, bounds: Map<Int, RowBounds>, skip: Int): Int {
    return bounds.keys.filter { it != skip }.sorted().indexOf(itemIndex)
}

internal fun packedRowBounds(
    frozen: Map<Int, RowBounds>,
    from: Int,
    extracting: Boolean,
    stride: Float
): Map<Int, RowBounds> {
    return frozen
}

internal data class DragHit(val group: Int?, val insert: Int)

internal fun insertSlotForCenter(
    center: Float,
    rows: List<Map.Entry<Int, RowBounds>>,
    rowHeight: Float
): Int {
    if (rows.isEmpty()) return 0
    rows.forEachIndexed { i, (_, row) ->
        if (center <= row.top + rowHeight / 2f) return i
    }
    return rows.size
}

internal fun overlayOverlapPx(overlayTop: Float, row: RowBounds, overlayHeight: Float): Float {
    val overlayBottom = overlayTop + overlayHeight
    val rowBottom = row.top + row.height
    return (minOf(overlayBottom, rowBottom) - maxOf(overlayTop, row.top)).coerceAtLeast(0f)
}

internal fun dragHit(
    overlayTop: Float,
    packed: Map<Int, RowBounds>,
    rowHeight: Float,
    currentGroup: Int,
    canTarget: (Int) -> Boolean
): DragHit {
    val rows = packed.entries.sortedBy { it.value.top }
    if (rows.isEmpty()) return DragHit(null, 0)
    val insert = insertSlotForCenter(overlayTop + rowHeight / 2f, rows, rowHeight)
    fun slotOf(index: Int) = rows.indexOfFirst { it.key == index }.coerceAtLeast(0)
    fun groupHit(index: Int): DragHit {
        if (currentGroup == index && canTarget(index)) return DragHit(currentGroup, insert)
        return DragHit(index.takeIf { canTarget(it) }, insert)
    }
    val scored = rows.map { it to overlayOverlapPx(overlayTop, it.value, rowHeight) }
        .filter { it.second > 0f }
        .sortedByDescending { it.second }
    if (scored.isEmpty()) return DragHit(null, insert)
    val (best, bestPx) = scored.first()
    if (bestPx >= rowHeight * 0.45f) return groupHit(best.key)
    val straddling = scored.filter { it.second >= rowHeight * 0.28f }
    if (straddling.size >= 2) {
        val lower = straddling.maxBy { it.first.value.top }.first
        return DragHit(null, slotOf(lower.key))
    }
    return groupHit(best.key)
}

internal fun groupCandidate(
    overlayTop: Float,
    from: Int,
    extracting: Boolean,
    bounds: Map<Int, RowBounds>,
    rowHeight: Float,
    current: Int,
    canTarget: (Int) -> Boolean
): Int? {
    if (bounds.isEmpty()) return null
    val overlayCenter = overlayTop + rowHeight / 2f
    val acquirePad = rowHeight * 0.22f
    val stayPad = rowHeight * 0.08f
    fun contains(index: Int, pad: Float): Boolean {
        val row = bounds[index] ?: return false
        return overlayCenter >= row.top + pad && overlayCenter <= row.top + row.height - pad
    }
    val eligible = bounds.keys.filter { index ->
        (index != from || extracting) && canTarget(index)
    }.sorted()
    if (current in eligible && contains(current, stayPad)) return current
    return eligible.firstOrNull { contains(it, acquirePad) }
}

internal fun insertSlotAlongRow(
    pointerX: Float,
    rowLeft: Float,
    rowWidth: Float,
    remaining: Int,
    spacing: Float
): Int {
    if (remaining <= 0 || rowWidth <= 0f) return 0
    val visualCount = remaining + 1
    val width = slotWidthPx(rowWidth, visualCount, spacing)
    val stride = width + spacing
    if (stride <= 0f) return 0
    return ((pointerX - rowLeft - width / 2f) / stride).roundToInt().coerceIn(0, remaining)
}

