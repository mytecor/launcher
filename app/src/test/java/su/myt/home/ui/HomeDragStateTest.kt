package su.myt.home.ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import su.myt.home.model.App
import su.myt.home.model.AppGroup

class HomeDragStateTest {
    @Test
    fun destinationIsRevealedBeforeOverlayIsRemoved() = runBlocking {
        val clock = BroadcastFrameClock()
        val first = mock<App> { on { id } doReturn "first" }
        val second = mock<App> { on { id } doReturn "second" }
        val items = mutableListOf(AppGroup(mutableListOf(first)), AppGroup(mutableListOf(second)))
        var commits = 0
        val state = HomeDragState(
            density = Density(1f),
            scope = CoroutineScope(coroutineContext + clock),
            getItems = { items },
            onMoveItem = { from, to ->
                items.add(to, items.removeAt(from))
                commits++
                true
            },
            onGroupItems = { _, _, _ -> false },
            onCanGroup = { _, _ -> false },
            onExtractToPosition = { _, _, _ -> },
            onExtractToGroup = { _, _, _, _ -> },
            onReorderInGroup = { _, _, _ -> },
            onDragActiveChange = { }
        )
        state.listWidth = 360f
        state.rowBounds[0] = RowBounds(100f, 64f)
        state.rowBounds[1] = RowBounds(172f, 64f)
        state.onDragStart(0, first, Offset(16f, 100f), 328f, Offset(100f, 32f), false, 0)
        state.insertRow = 2
        state.onOverlayDragEnd()
        var frame = 0L
        while (!state.revealDrop && frame < 600) {
            clock.sendFrame(frame++ * 16_666_667L)
            yield()
        }
        assertTrue("Destination must become visible while the overlay still covers it", state.revealDrop)
        assertNotNull(state.drag)
        assertTrue(state.dragActive.value)
        assertEquals(listOf(second, first), items.map { it.apps.single() })
        assertEquals(1, commits)
        clock.sendFrame(frame++ * 16_666_667L)
        yield()
        assertNotNull("Keep the overlay through the destination's first layout frame", state.drag)
        clock.sendFrame(frame * 16_666_667L)
        yield()
        assertNull(state.drag)
        assertFalse(state.dragActive.value)
        assertEquals(1, commits)
    }
}
