package su.myt.home.ui

import androidx.compose.animation.core.Animatable
import su.myt.home.model.App

internal data class DragOverlay(
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

internal sealed class GroupVisual {
    data class Item(val appIndex: Int, val app: App) : GroupVisual()
    data object Gap : GroupVisual()
}

internal fun GroupVisual.slotKey(): String = when (this) {
    GroupVisual.Gap -> "gap"
    is GroupVisual.Item -> app.id
}

internal class SlotAnim(x: Float, width: Float) {
    val x = Animatable(x)
    val width = Animatable(width)
}

internal fun groupVisuals(
    apps: List<App>,
    draggingAppIndex: Int,
    insertSlot: Int
): List<GroupVisual> {
    if (apps.size == 1 && draggingAppIndex == 0) {
        val gapAt = if (insertSlot >= 0) insertSlot.coerceIn(0, 1) else -1
        return buildList {
            if (gapAt == 0) add(GroupVisual.Gap)
            add(GroupVisual.Item(0, apps[0]))
            if (gapAt == 1) add(GroupVisual.Gap)
        }
    }
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

internal sealed class PinnedVisual {
    data class Group(val index: Int) : PinnedVisual()
    data object Gap : PinnedVisual()
}

internal fun pinnedVisuals(
    count: Int,
    draggingFrom: Int,
    extracting: Boolean,
    insertRow: Int
): List<PinnedVisual> {
    if (count <= 0) return emptyList()
    if (extracting) {
        val rows = (0 until count).toList()
        val gapAt = if (insertRow >= 0) insertRow.coerceIn(0, rows.size) else -1
        return buildList {
            rows.forEachIndexed { i, idx ->
                if (i == gapAt) add(PinnedVisual.Gap)
                add(PinnedVisual.Group(idx))
            }
            if (gapAt == rows.size) add(PinnedVisual.Gap)
        }
    }
    val rows = (0 until count).toMutableList()
    if (draggingFrom in rows.indices && insertRow >= 0) {
        val dest = moveDestination(draggingFrom, insertRow, count)
        if (dest != draggingFrom) {
            rows.removeAt(draggingFrom)
            rows.add(dest, draggingFrom)
        }
    }
    return rows.map { PinnedVisual.Group(it) }
}

internal data class DragVisual(
    val width: Float,
    val inGroup: Boolean
)
