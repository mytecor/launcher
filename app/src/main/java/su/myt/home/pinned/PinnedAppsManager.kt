package su.myt.home.pinned

import su.myt.home.data.PinnedAppsStorage
import su.myt.home.model.App
import java.util.Collections

class PinnedAppsManager(private val storage: PinnedAppsStorage) {
    private val pinnedOrder: MutableList<String> = storage.loadPinnedOrder()

    fun getPinnedIds(): List<String> = pinnedOrder.toList()

    fun isPinned(app: App): Boolean {
        return app.id in pinnedOrder || (app.userSerial == 0L && app.component.flattenToString() in pinnedOrder)
    }

    fun syncWithLoadedApps(allApps: List<App>) {
        if (!storage.hasPinnedOrder() && pinnedOrder.isNotEmpty()) {
            val existingIds = pinnedOrder.toSet()
            val sorted = allApps.filter { it.id in existingIds || it.component.flattenToString() in existingIds }.map { it.id }
            pinnedOrder.clear()
            pinnedOrder.addAll(sorted)
            storage.savePinnedOrder(pinnedOrder)
        }
    }

    fun getPinnedApps(allApps: List<App>): MutableList<App> {
        val appMap = allApps.associateBy { it.id }
        val homeApps = mutableListOf<App>()
        for (id in pinnedOrder) {
            val app = appMap[id] ?: allApps.find { it.userSerial == 0L && it.component.flattenToString() == id }
            if (app != null) homeApps.add(app)
        }
        return homeApps
    }

    fun moveItem(items: MutableList<App>, from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        if (from < to) {
            for (i in from until to) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        pinnedOrder.clear()
        pinnedOrder.addAll(items.map { it.id })
        return true
    }

    fun saveOrder() {
        storage.savePinnedOrder(pinnedOrder)
    }

    /**
     * Toggles pin status.
     * Returns true if pinned, false if unpinned.
     */
    fun togglePin(app: App): Boolean {
        val wasPinned = isPinned(app)
        if (wasPinned) {
            pinnedOrder.removeAll { it == app.id || (app.userSerial == 0L && it == app.component.flattenToString()) }
        } else {
            pinnedOrder.add(app.id)
        }
        storage.savePinnedOrder(pinnedOrder)
        return !wasPinned
    }
}
