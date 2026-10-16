package su.myt.home.ui

import kotlin.math.roundToInt

internal fun slotWidthPx(rowWidth: Float, count: Int, spacingPx: Float): Float {
    if (count <= 0) return rowWidth
    return ((rowWidth - spacingPx * (count - 1)) / count).coerceAtLeast(0f)
}

internal data class RowBounds(val top: Float, val height: Float)

internal fun rowStride(bounds: Map<Int, RowBounds>, height: Float, spacing: Float): Float {
    val first = bounds.minByOrNull { it.key }
    val last = bounds.maxByOrNull { it.key }
    if (first != null && last != null && last.key > first.key && last.value.top > first.value.top) {
        return (last.value.top - first.value.top) / (last.key - first.key)
    }
    return height + spacing
}

internal fun rowTopAt(index: Int, bounds: Map<Int, RowBounds>, stride: Float): Float? {
    val anchor = bounds.minByOrNull { kotlin.math.abs(it.key - index) } ?: return null
    return anchor.value.top + (index - anchor.key) * stride
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

internal data class DragHit(val group: Int?, val insert: Int)

internal fun insertSlotForCenter(
    center: Float,
    rows: List<Map.Entry<Int, RowBounds>>
): Int {
    if (rows.isEmpty()) return 0
    rows.forEach { (index, row) ->
        if (center <= row.top + row.height / 2f) return index
    }
    return rows.last().key + 1
}

internal fun overlayOverlapPx(overlayTop: Float, row: RowBounds, overlayHeight: Float): Float {
    val overlayBottom = overlayTop + overlayHeight
    val rowBottom = row.top + row.height
    return (minOf(overlayBottom, rowBottom) - maxOf(overlayTop, row.top)).coerceAtLeast(0f)
}

internal fun dragHit(
    overlayTop: Float,
    bounds: Map<Int, RowBounds>,
    rowHeight: Float,
    canTarget: (Int) -> Boolean
): DragHit {
    val rows = bounds.entries.sortedBy { it.value.top }
    if (rows.isEmpty()) return DragHit(null, 0)
    val insert = insertSlotForCenter(overlayTop + rowHeight / 2f, rows)
    fun groupHit(index: Int) = DragHit(index.takeIf(canTarget), insert)
    val scored = rows.map { it to overlayOverlapPx(overlayTop, it.value, rowHeight) }
        .filter { it.second > 0f }
        .sortedByDescending { it.second }
    if (scored.isEmpty()) return DragHit(null, insert)
    val (best, bestPx) = scored.first()
    if (bestPx >= rowHeight * 0.45f) return groupHit(best.key)
    val straddling = scored.filter { it.second >= rowHeight * 0.28f }
    if (straddling.size >= 2) {
        val lower = straddling.maxBy { it.first.value.top }.first
        return DragHit(null, lower.key)
    }
    return groupHit(best.key)
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

