package su.myt.home.pinned

import android.content.ComponentName
import android.os.UserHandle
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import su.myt.home.data.PinnedStorage
import su.myt.home.model.App
import su.myt.home.model.AppGroup

class PinnedAppsManagerTest {

    private class FakeStorage(
        initial: List<List<String>> = emptyList()
    ) : PinnedStorage {
        var saved: List<List<String>> = initial.map { it.toMutableList() }
        var hasOrder: Boolean = initial.isNotEmpty()

        override fun hasPinnedOrder(): Boolean = hasOrder

        override fun loadPinnedOrder(): MutableList<MutableList<String>> {
            return saved.map { it.toMutableList() }.toMutableList()
        }

        override fun savePinnedOrder(pinnedOrder: List<List<String>>) {
            hasOrder = true
            saved = pinnedOrder.map { it.toMutableList() }
        }
    }

    private fun createApp(pkg: String, cls: String, label: String): App {
        val comp = mock(ComponentName::class.java)
        `when`(comp.flattenToString()).thenReturn("$pkg/$cls")
        val user = mock(UserHandle::class.java)
        return App(
            label = label,
            badgedLabel = label,
            component = comp,
            user = user,
            userSerial = 0L
        )
    }

    @Test
    fun testGroupingTwoApps() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val storage = FakeStorage(listOf(listOf(app1.id), listOf(app2.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1)),
            AppGroup(mutableListOf(app2))
        )

        assertTrue(manager.canGroup(items, 1, 0))
        val grouped = manager.groupItems(items, 1, 0)
        assertTrue(grouped)

        // After grouping, items should have 1 group with 2 apps
        assertEquals(1, items.size)
        assertEquals(2, items[0].apps.size)
        assertEquals(app1, items[0].apps[0])
        assertEquals(app2, items[0].apps[1])
        assertTrue(items[0].isGroup)

        // Storage order should be saved as 1 group
        assertEquals(listOf(listOf(app1.id, app2.id)), storage.saved)
    }

    @Test
    fun testReorderWithinGroup() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val app3 = createApp("com.app3", "Main3", "App 3")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id, app3.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(AppGroup(mutableListOf(app1, app2, app3)))

        assertTrue(manager.moveAppInGroup(items, 0, 0, 2))
        assertEquals(listOf(app2, app3, app1), items[0].apps)
        assertEquals(listOf(listOf(app2.id, app3.id, app1.id)), storage.saved)

        assertTrue(manager.moveAppInGroup(items, 0, 1, 0))
        assertEquals(listOf(app3, app2, app1), items[0].apps)
        assertEquals(listOf(listOf(app3.id, app2.id, app1.id)), storage.saved)

        assertFalse(manager.moveAppInGroup(items, 0, 1, 1))
        assertEquals(listOf(app3, app2, app1), items[0].apps)
    }

    @Test
    fun testMaxGroupSize() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val app3 = createApp("com.app3", "Main3", "App 3")
        val app4 = createApp("com.app4", "Main4", "App 4")
        val app5 = createApp("com.app5", "Main5", "App 5")

        val storage = FakeStorage(listOf(listOf(app1.id, app2.id, app3.id, app4.id), listOf(app5.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1, app2, app3, app4)),
            AppGroup(mutableListOf(app5))
        )

        assertFalse(manager.canGroup(items, 1, 0))
        assertFalse(manager.groupItems(items, 1, 0))
    }

    @Test
    fun testUngroupApp() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1, app2))
        )

        val ungrouped = manager.ungroupItem(items, 0, 1)
        assertTrue(ungrouped)

        // Now items should have 2 single groups
        assertEquals(2, items.size)
        assertEquals(1, items[0].apps.size)
        assertEquals(app1, items[0].apps[0])
        assertFalse(items[0].isGroup)

        assertEquals(1, items[1].apps.size)
        assertEquals(app2, items[1].apps[0])
        assertFalse(items[1].isGroup)

        assertEquals(listOf(listOf(app1.id), listOf(app2.id)), storage.saved)
    }

    @Test
    fun testUnpinAppFromGroup() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1, app2))
        )

        val unpinned = manager.unpinApp(items, 0, 1)
        assertTrue(unpinned)

        assertEquals(1, items.size)
        assertEquals(1, items[0].apps.size)
        assertEquals(app1, items[0].apps[0])
        assertFalse(items[0].isGroup)

        assertEquals(listOf(listOf(app1.id)), storage.saved)
    }

    @Test
    fun testMoveItem() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val storage = FakeStorage(listOf(listOf(app1.id), listOf(app2.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1)),
            AppGroup(mutableListOf(app2))
        )

        val moved = manager.moveItem(items, 0, 1)
        assertTrue(moved)
        manager.saveOrder()

        assertEquals(app2, items[0].apps[0])
        assertEquals(app1, items[1].apps[0])
        assertEquals(listOf(listOf(app2.id), listOf(app1.id)), storage.saved)
    }

    @Test
    fun testIsPinnedAndTogglePin() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id)))
        val manager = PinnedAppsManager(storage)

        assertTrue(manager.isPinned(app1))
        assertTrue(manager.isPinned(app2))

        val app3 = createApp("com.app3", "Main3", "App 3")
        assertFalse(manager.isPinned(app3))

        // Pin app3
        val pinned = manager.togglePin(app3)
        assertTrue(pinned)
        assertTrue(manager.isPinned(app3))

        // Unpin app2 (which is in a group)
        val unpinned = manager.togglePin(app2)
        assertFalse(unpinned)
        assertFalse(manager.isPinned(app2))
        assertTrue(manager.isPinned(app1))
    }

    @Test
    fun testSyncOrderFromGroups() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val app3 = createApp("com.app3", "Main3", "App 3")
        val storage = FakeStorage(listOf(listOf(app1.id), listOf(app2.id)))
        val manager = PinnedAppsManager(storage)

        val groups = listOf(
            AppGroup(mutableListOf(app2, app3)),
            AppGroup(mutableListOf(app1))
        )
        manager.syncOrderFromGroups(groups)

        assertEquals(listOf(listOf(app2.id, app3.id), listOf(app1.id)), storage.saved)
        assertEquals(listOf(app2.id, app3.id, app1.id), manager.getPinnedIds())
    }

    @Test
    fun testExtractAppToPositionAboveAndBelow() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val app3 = createApp("com.app3", "Main3", "App 3")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id), listOf(app3.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1, app2)),
            AppGroup(mutableListOf(app3))
        )

        // Extract app2 and place it at position 0 (above app1)
        val extractedAbove = manager.extractAppToPosition(items, 0, 1, 0)
        assertTrue(extractedAbove)
        assertEquals(3, items.size)
        assertEquals(app2, items[0].apps[0])
        assertEquals(app1, items[1].apps[0])
        assertEquals(app3, items[2].apps[0])
        assertEquals(listOf(listOf(app2.id), listOf(app1.id), listOf(app3.id)), storage.saved)
    }

    @Test
    fun testExtractAppToGroup() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val app3 = createApp("com.app3", "Main3", "App 3")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id), listOf(app3.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1, app2)),
            AppGroup(mutableListOf(app3))
        )

        assertTrue(manager.extractAppToGroup(items, 0, 1, 1))
        assertEquals(2, items.size)
        assertEquals(listOf(app1), items[0].apps)
        assertEquals(listOf(app3, app2), items[1].apps)
        assertEquals(listOf(listOf(app1.id), listOf(app3.id, app2.id)), storage.saved)
    }

    @Test
    fun testExtractAppToGroupAtSlot() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val app3 = createApp("com.app3", "Main3", "App 3")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id), listOf(app3.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1, app2)),
            AppGroup(mutableListOf(app3))
        )

        assertTrue(manager.extractAppToGroup(items, 0, 1, 1, 0))
        assertEquals(listOf(app1), items[0].apps)
        assertEquals(listOf(app2, app3), items[1].apps)
        assertEquals(listOf(listOf(app1.id), listOf(app2.id, app3.id)), storage.saved)
    }

    @Test
    fun testGroupItemsAtSlot() {
        val app1 = createApp("com.app1", "Main1", "App 1")
        val app2 = createApp("com.app2", "Main2", "App 2")
        val app3 = createApp("com.app3", "Main3", "App 3")
        val storage = FakeStorage(listOf(listOf(app1.id, app2.id), listOf(app3.id)))
        val manager = PinnedAppsManager(storage)

        val items = mutableListOf(
            AppGroup(mutableListOf(app1, app2)),
            AppGroup(mutableListOf(app3))
        )

        assertTrue(manager.groupItems(items, 1, 0, 1))
        assertEquals(1, items.size)
        assertEquals(listOf(app1, app3, app2), items[0].apps)
        assertEquals(listOf(listOf(app1.id, app3.id, app2.id)), storage.saved)
    }
}
