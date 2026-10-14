package su.myt.home.pinned

import su.myt.home.data.PinnedStorage
import su.myt.home.model.App
import su.myt.home.model.AppGroup
import java.util.Collections

class PinnedAppsManager(private val storage: PinnedStorage) {
    companion object {
        const val MAX_GROUP_SIZE = 4
    }

    private val pinnedOrder: MutableList<MutableList<String>> = storage.loadPinnedOrder()

    fun getPinnedIds(): List<String> = pinnedOrder.flatten()

    fun isPinned(app: App): Boolean {
        val allIds = getPinnedIds()
        return app.id in allIds || (app.userSerial == 0L && app.component.flattenToString() in allIds)
    }

    fun syncWithLoadedApps(allApps: List<App>) {
        if (!storage.hasPinnedOrder() && pinnedOrder.isNotEmpty()) {
            val existingIds = pinnedOrder.flatten().toSet()
            val sorted = allApps.filter { it.id in existingIds || it.component.flattenToString() in existingIds }
                .map { mutableListOf(it.id) }
            pinnedOrder.clear()
            pinnedOrder.addAll(sorted)
            storage.savePinnedOrder(pinnedOrder)
            return
        }

        val validIds = allApps.map { it.id }.toSet()
        val validLegacyIds = allApps.filter { it.userSerial == 0L }.map { it.component.flattenToString() }.toSet()
        var changed = false
        val iterator = pinnedOrder.iterator()
        while (iterator.hasNext()) {
            val group = iterator.next()
            val beforeSize = group.size
            group.removeAll { id -> id !in validIds && id !in validLegacyIds }
            if (group.size != beforeSize) changed = true
            if (group.isEmpty()) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            storage.savePinnedOrder(pinnedOrder)
        }
    }

    fun getPinnedGroups(allApps: List<App>): MutableList<AppGroup> {
        val appMap = allApps.associateBy { it.id }
        val homeGroups = mutableListOf<AppGroup>()
        for (group in pinnedOrder) {
            val resolved = mutableListOf<App>()
            for (id in group) {
                val app = appMap[id] ?: allApps.find { it.userSerial == 0L && it.component.flattenToString() == id }
                if (app != null) resolved.add(app)
            }
            if (resolved.isNotEmpty()) {
                homeGroups.add(AppGroup(resolved))
            }
        }
        return homeGroups
    }

    fun insertRow(index: Int, app: App) {
        val insertIdx = index.coerceIn(0, pinnedOrder.size)
        pinnedOrder.add(insertIdx, mutableListOf(app.id))
        saveOrder()
    }

    fun addAppToGroup(groupIndex: Int, slotIndex: Int = -1, app: App) {
        if (groupIndex in pinnedOrder.indices) {
            val group = pinnedOrder[groupIndex]
            val insertIdx = if (slotIndex < 0 || slotIndex > group.size) group.size else slotIndex
            group.add(insertIdx, app.id)
            saveOrder()
        } else {
            insertRow(pinnedOrder.size, app)
        }
    }

    fun removeAppFromGroup(groupIndex: Int, appIndex: Int) {
        if (groupIndex in pinnedOrder.indices) {
            val group = pinnedOrder[groupIndex]
            if (appIndex in group.indices) {
                group.removeAt(appIndex)
                if (group.isEmpty()) {
                    pinnedOrder.removeAt(groupIndex)
                }
                saveOrder()
            }
        }
    }

    fun moveItem(items: MutableList<AppGroup>, from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        if (from < to) {
            for (i in from until to) {
                Collections.swap(items, i, i + 1)
                if (i in pinnedOrder.indices && (i + 1) in pinnedOrder.indices) {
                    Collections.swap(pinnedOrder, i, i + 1)
                }
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(items, i, i - 1)
                if (i in pinnedOrder.indices && (i - 1) in pinnedOrder.indices) {
                    Collections.swap(pinnedOrder, i, i - 1)
                }
            }
        }
        return true
    }

    fun canGroup(items: List<AppGroup>, from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val fromSize = items[from].apps.size
        val toSize = items[to].apps.size
        return (fromSize + toSize) <= MAX_GROUP_SIZE
    }

    fun groupItems(items: MutableList<AppGroup>, from: Int, to: Int, slotIndex: Int = -1): Boolean {
        if (!canGroup(items, from, to)) return false

        val fromItem = items[from]
        val toItem = items[to]
        val insertIdx = insertIndex(toItem.apps.size, slotIndex)
        toItem.apps.addAll(insertIdx, fromItem.apps)
        items.removeAt(from)

        if (from in pinnedOrder.indices && to in pinnedOrder.indices) {
            val fromIds = pinnedOrder[from]
            pinnedOrder[to].addAll(insertIdx, fromIds)
            pinnedOrder.removeAt(from)
        }
        saveOrder()
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

        syncOrderFromGroups(items)
        return true
    }

    fun extractAppToGroup(
        items: MutableList<AppGroup>,
        groupIndex: Int,
        appIndex: Int,
        targetGroupIndex: Int,
        slotIndex: Int = -1
    ): Boolean {
        if (groupIndex !in items.indices || targetGroupIndex !in items.indices || groupIndex == targetGroupIndex) return false
        val group = items[groupIndex]
        val targetGroup = items[targetGroupIndex]
        if (appIndex !in group.apps.indices || group.apps.size <= 1) return false
        if (targetGroup.apps.size >= MAX_GROUP_SIZE) return false

        val extracted = group.apps.removeAt(appIndex)
        targetGroup.apps.add(insertIndex(targetGroup.apps.size, slotIndex), extracted)
        if (group.apps.isEmpty()) {
            items.removeAt(groupIndex)
        }
        syncOrderFromGroups(items)
        return true
    }

    fun unpinApp(items: MutableList<AppGroup>, groupIndex: Int, appIndex: Int): Boolean {
        if (groupIndex !in items.indices) return false
        val group = items[groupIndex]
        if (appIndex !in group.apps.indices) return false

        group.apps.removeAt(appIndex)
        if (groupIndex in pinnedOrder.indices) {
            val orderGroup = pinnedOrder[groupIndex]
            if (appIndex in orderGroup.indices) {
                orderGroup.removeAt(appIndex)
            }
        }

        if (group.apps.isEmpty()) {
            items.removeAt(groupIndex)
            if (groupIndex in pinnedOrder.indices) {
                pinnedOrder.removeAt(groupIndex)
            }
        }
        saveOrder()
        return true
    }

    fun syncOrderFromGroups(groups: List<AppGroup>) {
        pinnedOrder.clear()
        for (group in groups) {
            val ids = group.apps.map { it.id }.filter { it.isNotEmpty() }
            if (ids.isNotEmpty()) {
                pinnedOrder.add(ids.toMutableList())
            }
        }
        saveOrder()
    }

    fun saveOrder() {
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
                group.removeAll { it == app.id || (app.userSerial == 0L && it == app.component.flattenToString()) }
                if (group.isEmpty()) {
                    iterator.remove()
                }
            }
        } else {
            pinnedOrder.add(mutableListOf(app.id))
        }
        storage.savePinnedOrder(pinnedOrder)
        return !wasPinned
    }
}
