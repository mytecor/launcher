package su.myt.home.pinned

import android.content.ComponentName
import android.os.UserHandle
import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
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
        var saveCount: Int = 0

        override fun hasPinnedOrder(): Boolean = hasOrder

        override fun loadPinnedOrder(): MutableList<MutableList<String>> {
            return saved.map { it.toMutableList() }.toMutableList()
        }

        override fun savePinnedOrder(pinnedOrder: List<List<String>>) {
            hasOrder = true
            saveCount++
            saved = pinnedOrder.map { it.toMutableList() }
        }
    }

    private fun createApp(pkg: String, cls: String, label: String, userSerial: Long = 0L): App {
        val comp = mock(ComponentName::class.java)
        `when`(comp.flattenToString()).thenReturn("$pkg/$cls")
        val user = mock(UserHandle::class.java)
        return App(
            label = label,
            badgedLabel = label,
            component = comp,
            user = user,
            userSerial = userSerial
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

    @Test
    fun legacyMigrationKeepsMissingPinsAndDoesNotPinAnotherProfile() {
        val personal = createApp("com.shared", "Main", "Shared")
        val work = createApp("com.shared", "Main", "Shared", userSerial = 10L)
        val missing = createApp("com.missing", "Main", "Missing")
        val storage = FakeStorage(listOf(listOf(personal.id), listOf(missing.id))).apply { hasOrder = false }
        val manager = PinnedAppsManager(storage)

        manager.syncWithLoadedApps(listOf(work, personal))

        assertTrue(manager.isPinned(personal))
        assertFalse(manager.isPinned(work))
        assertEquals(listOf(listOf(personal.id), listOf(missing.id)), storage.saved)
    }

    @Test
    fun unavailableProfilePinsReturnInTheirOriginalGroups() {
        val personal = createApp("com.personal", "Main", "Personal")
        val work = createApp("com.work", "Main", "Work", userSerial = 10L)
        val storage = FakeStorage(listOf(listOf(personal.id, work.id)))
        val manager = PinnedAppsManager(storage)

        manager.syncWithLoadedApps(listOf(personal))
        assertEquals(listOf(personal), manager.getPinnedGroups(listOf(personal)).single().apps)
        assertTrue(manager.isPinned(work))
        assertEquals(0, storage.saveCount)

        manager.syncWithLoadedApps(listOf(personal, work))
        assertEquals(listOf(personal, work), manager.getPinnedGroups(listOf(personal, work)).single().apps)
    }

    @Test
    fun movingVisibleRowsPreservesHiddenRowsAndSavesOnce() {
        val first = createApp("com.first", "Main", "First")
        val second = createApp("com.second", "Main", "Second")
        val hidden = createApp("com.hidden", "Main", "Hidden", userSerial = 10L)
        val storage = FakeStorage(listOf(listOf(hidden.id), listOf(first.id), listOf(second.id)))
        val manager = PinnedAppsManager(storage)
        val items = manager.getPinnedGroups(listOf(first, second))

        assertTrue(manager.moveItem(items, 0, 1))
        assertEquals(listOf(listOf(hidden.id), listOf(second.id), listOf(first.id)), storage.saved)
        assertEquals(1, storage.saveCount)
        assertFalse(manager.moveItem(items, 0, 0))
        assertEquals(1, storage.saveCount)
        assertEquals(
            listOf(hidden, second, first),
            manager.getPinnedGroups(listOf(first, second, hidden)).flatMap { it.apps }
        )
    }

    @Test
    fun groupingUnpinningAndTogglingPreserveHiddenGroupMembers() {
        val first = createApp("com.first", "Main", "First")
        val second = createApp("com.second", "Main", "Second")
        val hidden = createApp("com.hidden", "Main", "Hidden", userSerial = 10L)
        val storage = FakeStorage(listOf(listOf(first.id, hidden.id), listOf(second.id)))
        val manager = PinnedAppsManager(storage)
        val items = manager.getPinnedGroups(listOf(first, second))

        assertTrue(manager.groupItems(items, 1, 0))
        assertEquals(listOf(listOf(first.id, hidden.id, second.id)), storage.saved)
        assertTrue(manager.unpinApp(items, 0, 0))
        assertEquals(listOf(listOf(hidden.id, second.id)), storage.saved)
        assertTrue(manager.togglePin(first))
        assertEquals(listOf(listOf(hidden.id, second.id), listOf(first.id)), storage.saved)
        assertFalse(manager.togglePin(first))
        assertEquals(listOf(listOf(hidden.id, second.id)), storage.saved)
        manager.syncOrderFromGroups(items)
        assertEquals(listOf(listOf(hidden.id, second.id)), storage.saved)
    }

    @Test
    fun hiddenMembersCountTowardTheGroupLimit() {
        val first = createApp("com.first", "Main", "First")
        val second = createApp("com.second", "Main", "Second")
        val hidden = (1..3).map { createApp("com.hidden$it", "Main", "Hidden $it", userSerial = 10L) }
        val storage = FakeStorage(listOf(listOf(first.id) + hidden.map { it.id }, listOf(second.id)))
        val manager = PinnedAppsManager(storage)
        val items = manager.getPinnedGroups(listOf(first, second))

        assertFalse(manager.canGroup(items, 1, 0))
        assertFalse(manager.groupItems(items, 1, 0))
        assertEquals(0, storage.saveCount)
    }

    @Test
    fun syncingExplicitlyRestoredAppDoesNotDuplicateItsHiddenPin() {
        val first = createApp("com.first", "Main", "First")
        val hidden = createApp("com.hidden", "Main", "Hidden", userSerial = 10L)
        val storage = FakeStorage(listOf(listOf(first.id, hidden.id)))
        val manager = PinnedAppsManager(storage)
        manager.getPinnedGroups(listOf(first))

        manager.syncOrderFromGroups(listOf(AppGroup(mutableListOf(first, hidden))))

        assertEquals(listOf(listOf(first.id, hidden.id)), storage.saved)
    }

    @Test
    fun persistedLegacyIdMigratesOnlyToTheCurrentSecondaryUser() {
        val current = createApp("com.shared", "Main", "Shared", userSerial = 8L)
        val work = createApp("com.shared", "Main", "Shared", userSerial = 12L)
        val legacyId = current.component.flattenToString()
        val storage = FakeStorage(listOf(listOf(legacyId), listOf(current.id)))
        val manager = PinnedAppsManager(storage)
        mockStatic(Process::class.java).use { process ->
            process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(current.user)

            manager.syncWithLoadedApps(listOf(work, current))
        }

        assertEquals(listOf(listOf(current.id)), storage.saved)
        assertTrue(manager.isPinned(current))
        assertFalse(manager.isPinned(work))
    }

    @Test
    fun unresolvedLegacyIdDoesNotMigrateToAnotherProfile() {
        val current = createApp("com.current", "Main", "Current", userSerial = 8L)
        val work = createApp("com.shared", "Main", "Shared", userSerial = 12L)
        val legacyId = work.component.flattenToString()
        val storage = FakeStorage(listOf(listOf(legacyId)))
        val manager = PinnedAppsManager(storage)
        mockStatic(Process::class.java).use { process ->
            process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(current.user)

            manager.syncWithLoadedApps(listOf(work, current))
        }

        assertEquals(listOf(listOf(legacyId)), storage.saved)
        assertFalse(manager.isPinned(work))
        assertEquals(0, storage.saveCount)
    }

    @Test
    fun extractionHoverAndCommitBothRejectTargetsFilledByHiddenPins() {
        val target = createApp("com.target", "Main", "Target")
        val first = createApp("com.first", "Main", "First")
        val second = createApp("com.second", "Main", "Second")
        val hidden = (1..3).map { createApp("com.hidden$it", "Main", "Hidden $it", userSerial = 10L) }
        val initial = listOf(listOf(target.id) + hidden.map { it.id }, listOf(first.id, second.id))
        val storage = FakeStorage(initial)
        val manager = PinnedAppsManager(storage)
        val items = manager.getPinnedGroups(listOf(target, first, second))

        assertFalse(manager.canGroup(items, 1, 0, extracting = true))
        assertFalse(manager.extractAppToGroup(items, 1, 0, 0))
        assertEquals(initial, storage.saved)
        assertEquals(listOf(first, second), items[1].apps)
        assertEquals(0, storage.saveCount)
    }

    @Test
    fun extractionCapacityCountsOnlyTheMovedApp() {
        val apps = (1..5).map { createApp("com.app$it", "Main", "App $it") }
        val storage = FakeStorage(listOf(apps.take(3).map { it.id }, apps.drop(3).map { it.id }))
        val manager = PinnedAppsManager(storage)
        val items = manager.getPinnedGroups(apps)

        assertFalse(manager.canGroup(items, 1, 0))
        assertTrue(manager.canGroup(items, 1, 0, extracting = true))
        assertTrue(manager.extractAppToGroup(items, 1, 0, 0))
        assertEquals(4, items[0].apps.size)
        assertFalse(manager.canGroup(items, 1, 0, extracting = true))
    }

    @Test
    fun extractionKeepsHiddenMembersInTheSourceWithoutOverfillingTheTarget() {
        val targetApps = (1..3).map { createApp("com.target$it", "Main", "Target $it") }
        val extracted = createApp("com.extracted", "Main", "Extracted")
        val remaining = createApp("com.remaining", "Main", "Remaining")
        val hidden = (1..2).map { createApp("com.hidden$it", "Main", "Hidden $it", userSerial = 10L) }
        val sourceIds = listOf(hidden[0].id, extracted.id, hidden[1].id, remaining.id)
        val storage = FakeStorage(listOf(targetApps.map { it.id }, sourceIds))
        val manager = PinnedAppsManager(storage)
        val visible = targetApps + listOf(extracted, remaining)
        val items = manager.getPinnedGroups(visible)

        assertTrue(manager.canGroup(items, 1, 0, extracting = true))
        assertTrue(manager.extractAppToGroup(items, 1, 0, 0))

        assertEquals(
            listOf(targetApps.map { it.id } + extracted.id, listOf(hidden[0].id, hidden[1].id, remaining.id)),
            storage.saved
        )
        assertTrue(storage.saved.all { it.size <= PinnedAppsManager.MAX_GROUP_SIZE })
        assertEquals(listOf(hidden[0], hidden[1], remaining), manager.getPinnedGroups(visible + hidden)[1].apps)
    }

    @Test
    fun extractingToAnEarlierNewRowLeavesHiddenMembersWithTheSource() {
        val extracted = createApp("com.extracted", "Main", "Extracted")
        val remaining = createApp("com.remaining", "Main", "Remaining")
        val hidden = createApp("com.hidden", "Main", "Hidden", userSerial = 10L)
        val storage = FakeStorage(listOf(listOf(hidden.id, extracted.id, remaining.id)))
        val manager = PinnedAppsManager(storage)
        val items = manager.getPinnedGroups(listOf(extracted, remaining))

        assertTrue(manager.extractAppToPosition(items, 0, 0, 0))

        assertEquals(listOf(listOf(extracted.id), listOf(hidden.id, remaining.id)), storage.saved)
    }
}
