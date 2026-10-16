package su.myt.home.pinned

import android.os.Process
import su.myt.home.data.PinnedStorage
import su.myt.home.model.App
import su.myt.home.model.AppGroup

class PinnedAppsManager(private val storage: PinnedStorage) {
    companion object {
        const val MAX_GROUP_SIZE = 4
    }

    private val pinnedOrder: MutableList<MutableList<String>> = storage.loadPinnedOrder()

    private var availableAppIds: Set<String>? = null
    private data class HiddenAnchor(val oldMember: String, val remainingMember: String)

    fun getPinnedIds(): List<String> = pinnedOrder.flatten()

    fun isPinned(app: App): Boolean = pinnedOrder.any { app.id in it }

    fun syncWithLoadedApps(allApps: List<App>) {
        availableAppIds = allApps.mapTo(mutableSetOf()) { it.id }
        val hasOrder = storage.hasPinnedOrder()
        val currentUser = Process.myUserHandle()
        val currentProfileIds = allApps.filter { it.userSerial != 0L && it.user == currentUser }
            .associate { it.component.flattenToString() to it.id }
        // Older PackageManager fallbacks wrote unprefixed IDs even for a secondary current user.
        val seen = mutableSetOf<String>()
        val migrated = pinnedOrder.mapNotNull { group ->
            group.map { currentProfileIds[it] ?: it }.filter { seen.add(it) }
                .takeIf { it.isNotEmpty() }?.toMutableList()
        }
        var changed = migrated != pinnedOrder
        if (changed) {
            pinnedOrder.clear()
            pinnedOrder.addAll(migrated)
        }
        if (!hasOrder && pinnedOrder.isNotEmpty()) {
            val existingIds = getPinnedIds().toSet()
            val resolvedIds = allApps.map { it.id }.filter { it in existingIds }
            val unresolvedIds = existingIds - resolvedIds.toSet()
            pinnedOrder.clear()
            (resolvedIds + unresolvedIds).forEach { pinnedOrder.add(mutableListOf(it)) }
            changed = true
        }
        if (changed) saveOrder()
        // A missing app may belong to a paused/locked profile. Keep its pin until explicitly unpinned.
    }

    fun getPinnedGroups(allApps: List<App>): MutableList<AppGroup> {
        val appMap = allApps.associateBy { it.id }
        availableAppIds = appMap.keys
        return pinnedOrder.mapNotNullTo(mutableListOf()) { group ->
            group.mapNotNullTo(mutableListOf()) { appMap[it] }
                .takeIf { it.isNotEmpty() }
                ?.let { AppGroup(it) }
        }
    }

    fun moveItem(items: MutableList<AppGroup>, from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        items.add(to, items.removeAt(from))
        syncOrderFromGroups(items)
        return true
    }

    fun canGroup(items: List<AppGroup>, from: Int, to: Int, extracting: Boolean = false): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        if (extracting && !items[from].isGroup) return false
        val movedSize = if (extracting) 1 else storedGroupSize(items[from])
        return movedSize + storedGroupSize(items[to]) <= MAX_GROUP_SIZE
    }

    fun groupItems(items: MutableList<AppGroup>, from: Int, to: Int, slotIndex: Int = -1): Boolean {
        if (!canGroup(items, from, to)) return false

        val fromItem = items[from]
        val toItem = items[to]
        val insertIdx = insertIndex(toItem.apps.size, slotIndex)
        toItem.apps.addAll(insertIdx, fromItem.apps)
        items.removeAt(from)

        syncOrderFromGroups(items)
        return true
    }

    fun moveAppInGroup(
        items: MutableList<AppGroup>,
        groupIndex: Int,
        appIndex: Int,
        slotIndex: Int
    ): Boolean {
        if (groupIndex !in items.indices) return false
        val group = items[groupIndex]
        if (appIndex !in group.apps.indices || group.apps.size <= 1) return false
        val before = group.apps.toList()
        val app = group.apps.removeAt(appIndex)
        group.apps.add(insertIndex(group.apps.size, slotIndex), app)
        if (group.apps == before) return false
        syncOrderFromGroups(items)
        return true
    }

    fun ungroupItem(items: MutableList<AppGroup>, groupIndex: Int, appIndex: Int): Boolean {
        return extractAppToPosition(items, groupIndex, appIndex, groupIndex + 1)
    }

    fun extractAppToPosition(
        items: MutableList<AppGroup>,
        groupIndex: Int,
        appIndex: Int,
        targetPosition: Int
    ): Boolean {
        if (groupIndex !in items.indices) return false
        val group = items[groupIndex]
        if (appIndex !in group.apps.indices || group.apps.size <= 1) return false

        val extracted = group.apps.removeAt(appIndex)
        val insertPos = targetPosition.coerceIn(0, items.size)
        items.add(insertPos, AppGroup(mutableListOf(extracted)))

        syncOrderFromGroups(items, HiddenAnchor(extracted.id, group.apps.first().id))
        return true
    }

    fun extractAppToGroup(
        items: MutableList<AppGroup>,
        groupIndex: Int,
        appIndex: Int,
        targetGroupIndex: Int,
        slotIndex: Int = -1
    ): Boolean {
        if (!canGroup(items, groupIndex, targetGroupIndex, extracting = true)) return false
        val group = items[groupIndex]
        val targetGroup = items[targetGroupIndex]
        if (appIndex !in group.apps.indices) return false

        val extracted = group.apps.removeAt(appIndex)
        targetGroup.apps.add(insertIndex(targetGroup.apps.size, slotIndex), extracted)
        syncOrderFromGroups(items, HiddenAnchor(extracted.id, group.apps.first().id))
        return true
    }

    fun unpinApp(items: MutableList<AppGroup>, groupIndex: Int, appIndex: Int): Boolean {
        if (groupIndex !in items.indices) return false
        val group = items[groupIndex]
        if (appIndex !in group.apps.indices) return false

        group.apps.removeAt(appIndex)
        if (group.apps.isEmpty()) items.removeAt(groupIndex)
        syncOrderFromGroups(items)
        return true
    }

    fun syncOrderFromGroups(groups: List<AppGroup>) = syncOrderFromGroups(groups, null)

    private fun syncOrderFromGroups(groups: List<AppGroup>, hiddenAnchor: HiddenAnchor?) {
        val order = groups.map { group -> group.apps.map { it.id }.toMutableList() }.toMutableList()
        val unavailable = unavailableIds() - order.flatten().toSet()
        val hiddenGroups = mutableListOf<Pair<Int, MutableList<String>>>()
        for ((oldIndex, oldGroup) in pinnedOrder.withIndex()) {
            val hidden = oldGroup.filter { it in unavailable }
            if (hidden.isEmpty()) continue
            // Extraction leaves hidden members with the source, even when both new rows share one old ID.
            val anchored = hiddenAnchor?.takeIf { it.oldMember in oldGroup }
                ?.let { anchor -> order.firstOrNull { anchor.remainingMember in it } }
            val target = anchored ?: order.maxByOrNull { group -> group.count { it in oldGroup } }
                ?.takeIf { group -> group.any { it in oldGroup } }
            if (target == null) {
                hiddenGroups.add(oldIndex to hidden.toMutableList())
                continue
            }
            // Keep unavailable members alongside their group, preserving their relative slots.
            for ((index, id) in oldGroup.withIndex()) {
                if (id !in unavailable) continue
                val before = oldGroup.take(index).lastOrNull { it in target }
                val after = oldGroup.drop(index + 1).firstOrNull { it in target }
                val slot = when {
                    before != null -> target.indexOf(before) + 1
                    after != null -> target.indexOf(after)
                    else -> target.size
                }
                target.add(slot, id)
            }
        }
        // Fully hidden rows retain their place while the visible rows are edited.
        for ((index, group) in hiddenGroups) order.add(index.coerceAtMost(order.size), group)
        pinnedOrder.clear()
        pinnedOrder.addAll(order.filter { it.isNotEmpty() })
        saveOrder()
    }

    private fun unavailableIds(): Set<String> {
        val available = availableAppIds ?: return emptySet()
        return getPinnedIds().filterNotTo(mutableSetOf()) { it in available }
    }

    private fun storedGroupSize(group: AppGroup): Int {
        val ids = group.apps.mapTo(mutableSetOf()) { it.id }
        val hidden = unavailableIds()
        val stored = pinnedOrder.firstOrNull { row -> row.any { it in ids } }
        return group.apps.size + (stored?.count { it in hidden } ?: 0)
    }

    private fun saveOrder() {
        storage.savePinnedOrder(pinnedOrder)
    }

    private fun insertIndex(size: Int, slotIndex: Int): Int {
        if (slotIndex < 0) return size
        return slotIndex.coerceIn(0, size)
    }

    fun togglePin(app: App): Boolean {
        val wasPinned = isPinned(app)
        if (wasPinned) {
            val iterator = pinnedOrder.iterator()
            while (iterator.hasNext()) {
                val group = iterator.next()
                group.removeAll { it == app.id }
                if (group.isEmpty()) {
                    iterator.remove()
                }
            }
        } else {
            pinnedOrder.add(mutableListOf(app.id))
        }
        saveOrder()
        return !wasPinned
    }
}
