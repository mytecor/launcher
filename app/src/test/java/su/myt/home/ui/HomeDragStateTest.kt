package su.myt.home.ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import su.myt.home.model.App
import su.myt.home.model.AppGroup

class HomeDragStateTest {
    private class Fixture(scope: CoroutineScope) {
        val first = app("first")
        val second = app("second")
        var items = mutableListOf(AppGroup(mutableListOf(first)), AppGroup(mutableListOf(second)))
        var commits = 0
        var failCommit = false
        val activeChanges = mutableListOf<Boolean>()
        val state = HomeDragState(
            density = Density(1f),
            scope = scope,
            getItems = { items },
            onMoveItem = { from, to ->
                commits++
                check(!failCommit) { "Persistence failure" }
                items.add(to, items.removeAt(from))
                true
            },
            onGroupItems = { _, _, _ -> false },
            onCanGroup = { _, _, _ -> false },
            onExtractToPosition = { _, _, _ -> commits++ },
            onExtractToGroup = { _, _, _, _ -> commits++ },
            onReorderInGroup = { _, _, _ -> commits++ },
            onDragActiveChange = { activeChanges += it }
        )

        init {
            state.listWidth = 360f
            state.rowBounds[0] = RowBounds(100f, 64f)
            state.rowBounds[1] = RowBounds(172f, 64f)
        }

        fun start(index: Int = 0) {
            val top = state.rowBounds[index]?.top ?: 100f
            state.onDragStart(0, items[index].apps.first(), Offset(16f, top), 100f, index, PointerId(7))
        }

        fun drop() {
            state.insertRow = 2
            state.onOverlayDragEnd()
        }
    }

    @Test
    fun onlyThePointerThatStartedTheDragCanMoveOrReleaseIt() = runBlocking {
        val f = Fixture(this)
        f.start()
        val other = pointerChange(99, pressed = false)
        val primary = pointerChange(7)
        f.state.onPointerChanges(listOf(other, primary))
        assertEquals(26f, f.state.drag!!.x, 0f)
        assertEquals(110f, f.state.drag!!.y, 0f)
        assertFalse(f.state.settling)
        assertTrue(primary.isConsumed)
        assertFalse(other.isConsumed)
        f.state.onDragCancel()
    }

    @Test
    fun consumedAllUpEventCancelsTheDragWithoutStartingADrop() = runBlocking {
        val f = Fixture(this)
        f.start()
        f.state.onPointerChanges(listOf(pointerChange(7, pressed = false, consumed = true)))
        assertNull(f.state.drag)
        assertFalse(f.state.settling)
        assertEquals(0, f.commits)
        assertEquals(listOf(true, false), f.activeChanges)
    }

    @Test
    fun destinationIsRevealedBeforeOverlayIsRemoved() = runBlocking {
        val clock = BroadcastFrameClock()
        val f = Fixture(CoroutineScope(coroutineContext + clock))
        f.start()
        f.drop()
        var frame = 0L
        while (!f.state.revealDrop && frame < 600) {
            clock.sendFrame(frame++ * 16_666_667L)
            yield()
        }
        assertTrue("Destination must become visible while the overlay still covers it", f.state.revealDrop)
        assertNotNull(f.state.drag)
        assertTrue(f.state.dragActive)
        assertEquals(listOf(f.second, f.first), f.items.map { it.apps.single() })
        assertEquals(1, f.commits)
        clock.sendFrame(frame++ * 16_666_667L)
        yield()
        assertNotNull("Keep the overlay through the destination's first layout frame", f.state.drag)
        clock.sendFrame(frame * 16_666_667L)
        yield()
        assertNull(f.state.drag)
        assertFalse(f.state.dragActive)
        assertEquals(listOf(true, false), f.activeChanges)
        assertEquals(1, f.commits)
    }

    @Test
    fun cancelledDragClearsOverlayAndCachedGeometryWithoutCommitting() = runBlocking {
        val f = Fixture(this)
        f.start()
        f.state.floatW = 64f
        f.state.gapBounds = RowBounds(28f, 64f)
        f.state.onDragCancel()
        f.state.onDragCancel()
        assertNull(f.state.drag)
        assertFalse(f.state.dragActive)
        assertNull(f.state.gapBounds)
        assertTrue(f.state.dragLayout.isEmpty())
        assertEquals(0f, f.state.floatW, 0f)
        assertEquals(0, f.commits)
        assertEquals(listOf(true, false), f.activeChanges)
    }

    @Test
    fun cancellingSettlementDoesNotCommitOrClearTheNextDrag() = runBlocking {
        val clock = BroadcastFrameClock()
        val f = Fixture(CoroutineScope(coroutineContext + clock))
        f.start()
        f.drop()
        yield()
        f.state.onDragCancel()
        f.start(1)
        yield()
        assertEquals(f.second, f.state.drag?.app)
        assertTrue(f.state.dragActive)
        assertFalse(f.state.settling)
        assertEquals(0, f.commits)
        assertEquals(listOf(true, false, true), f.activeChanges)
        f.state.onDragCancel()
    }

    @Test
    fun disposingScopeDuringSettlementCancelsDropAndClearsActiveFlag() = runBlocking {
        val job = Job()
        val clock = BroadcastFrameClock()
        val f = Fixture(CoroutineScope(coroutineContext + job + clock))
        f.start()
        f.drop()
        yield()
        job.cancel()
        job.join()
        assertNull(f.state.drag)
        assertFalse(f.state.dragActive)
        assertEquals(0, f.commits)
        assertEquals(listOf(true, false), f.activeChanges)
    }

    @Test
    fun alreadyDisposedScopeCannotLeaveSettlementStuck() = runBlocking {
        val job = Job().apply { cancel() }
        val f = Fixture(CoroutineScope(coroutineContext + job))
        f.start()
        assertTrue(f.state.dragActive)
        f.drop()
        assertNull(f.state.drag)
        assertFalse(f.state.dragActive)
        assertEquals(0, f.commits)
        assertEquals(listOf(true, false), f.activeChanges)
    }

    @Test
    fun replacingItemsDuringSettlementCannotCommitOldIndicesIntoSearchResults() = runBlocking {
        val clock = BroadcastFrameClock()
        val f = Fixture(CoroutineScope(coroutineContext + clock))
        f.start()
        f.drop()
        val searchResult = app("search-result")
        f.items = mutableListOf(AppGroup(mutableListOf(searchResult)))
        finishDrop(clock, f.state)
        assertEquals(0, f.commits)
        assertEquals(listOf(searchResult), f.items.single().apps)
    }

    @Test
    fun failedCommitIsNotRetriedByCleanup() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val job = SupervisorJob()
        val clock = BroadcastFrameClock()
        val handler = CoroutineExceptionHandler { _, failure -> failures += failure }
        val f = Fixture(CoroutineScope(coroutineContext + job + clock + handler))
        try {
            f.failCommit = true
            f.start()
            f.drop()
            finishDrop(clock, f.state)
            assertEquals(1, f.commits)
            assertEquals(1, failures.size)
            assertFalse(f.state.dragActive)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun dropTargetUsesAbsoluteIndicesInScrolledList() = runBlocking {
        val f = Fixture(this)
        f.items = (0..9).map { AppGroup(mutableListOf(app("app-$it"))) }.toMutableList()
        f.state.rowBounds.clear()
        f.state.rowBounds[5] = RowBounds(100f, 64f)
        f.state.rowBounds[6] = RowBounds(172f, 64f)
        f.state.rowBounds[7] = RowBounds(244f, 64f)
        f.start(6)
        f.state.insertRow = 8
        val (_, top, width) = f.state.dropTarget(f.state.drag!!)
        assertEquals(244f, top, 0f)
        assertEquals(328f, width, 0f)
        f.state.onDragCancel()
    }

    @Test
    fun extractionGapUsesAbsoluteIndicesInScrolledList() = runBlocking {
        val f = Fixture(this)
        f.items = (0..9).map { AppGroup(mutableListOf(app("app-$it"))) }.toMutableList()
        f.items[6].apps += app("group-member")
        f.state.rowBounds.clear()
        f.state.rowBounds[5] = RowBounds(100f, 64f)
        f.state.rowBounds[6] = RowBounds(172f, 64f)
        f.state.rowBounds[7] = RowBounds(244f, 64f)
        f.start(6)
        f.state.hoverGroup = -1
        f.state.insertRow = 7
        val (_, top, _) = f.state.dropTarget(f.state.drag!!)
        assertEquals(172f, top, 0f)
        f.state.onDragCancel()
    }

    private suspend fun finishDrop(clock: BroadcastFrameClock, state: HomeDragState) {
        var frame = 0L
        while (state.drag != null && frame < 600) {
            clock.sendFrame(frame++ * 16_666_667L)
            yield()
        }
        assertNull("Drop must finish within the animation frame budget", state.drag)
    }

    companion object {
        private fun app(id: String): App = mock { on { this.id } doReturn id }

        private fun pointerChange(id: Long, pressed: Boolean = true, consumed: Boolean = false) = PointerInputChange(
            id = PointerId(id),
            uptimeMillis = 10L,
            position = Offset(126f, 142f),
            pressed = pressed,
            previousUptimeMillis = 0L,
            previousPosition = Offset(116f, 132f),
            previousPressed = true,
            isInitiallyConsumed = consumed
        )
    }
}
