package su.myt.home.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragHitTestTest {

    private val height = 64f

    private fun row(top: Float, h: Float = height) = RowBounds(top, h)

    @Test
    fun hitTestPreservesModelIndicesWhenOnlyScrolledRowsAreVisible() {
        val visible = mapOf(5 to row(100f), 6 to row(172f), 7 to row(244f))
        assertEquals(DragHit(null, 5), dragHit(20f, visible, height) { false })
        assertEquals(DragHit(6, 6), dragHit(172f, visible, height) { true })
        assertEquals(DragHit(null, 6), dragHit(136f, visible, height) { true })
        assertEquals(DragHit(null, 8), dragHit(320f, visible, height) { true })
    }

    @Test
    fun rowGeometryPreservesModelIndicesAcrossMissingMeasurements() {
        val visible = mapOf(5 to row(100f), 7 to row(244f))
        val stride = rowStride(visible, height, 8f)
        assertEquals(72f, stride, 0f)
        assertEquals(172f, rowTopAt(6, visible, stride)!!, 0f)
        assertEquals(316f, rowTopAt(8, visible, stride)!!, 0f)
        assertEquals(28f, rowTopAt(4, visible, stride)!!, 0f)
    }

    @Test
    fun moveDestinationMatchesInsertSlot() {
        assertEquals(0, moveDestination(2, 0, 4))
        assertEquals(1, moveDestination(2, 1, 4))
        assertEquals(2, moveDestination(2, 2, 4))
        assertEquals(2, moveDestination(2, 3, 4))
        assertEquals(3, moveDestination(2, 4, 4))
        assertEquals(2, moveDestination(0, 3, 4))
        assertEquals(3, moveDestination(0, 4, 4))
        assertEquals(0, moveDestination(0, 0, 4))
        assertEquals(2, moveDestination(2, -1, 4))
    }

    @Test
    fun extractDestinationClampsAndFallsBackBelowGroup() {
        assertEquals(0, extractDestination(0, 0, 2))
        assertEquals(2, extractDestination(2, 0, 2))
        assertEquals(1, extractDestination(-1, 0, 2))
    }

    @Test
    fun horizontalSlotMatchesVisualCenters() {
        val rowLeft = 0f
        val rowWidth = 300f
        val spacing = 8f
        assertEquals(0, insertSlotAlongRow(20f, rowLeft, rowWidth, remaining = 2, spacing = spacing))
        assertEquals(1, insertSlotAlongRow(150f, rowLeft, rowWidth, remaining = 2, spacing = spacing))
        assertEquals(2, insertSlotAlongRow(280f, rowLeft, rowWidth, remaining = 2, spacing = spacing))
        assertEquals(0, insertSlotAlongRow(10f, rowLeft, rowWidth, remaining = 1, spacing = spacing))
        assertEquals(1, insertSlotAlongRow(290f, rowLeft, rowWidth, remaining = 1, spacing = spacing))
    }

    @Test
    fun dragHitTreatsCenterOnTopRowAsGroupNotBelow() {
        val bounds = mapOf(0 to row(100f), 1 to row(172f), 2 to row(244f))
        val onTop = dragHit(
            overlayTop = 100f,
            bounds = bounds,
            rowHeight = height,
            canTarget = { true }
        )
        assertEquals(0, onTop.group)
        assertEquals(0, onTop.insert)
        val lowerHalfOfTop = dragHit(
            overlayTop = 130f,
            bounds = bounds,
            rowHeight = height,
            canTarget = { true }
        )
        assertEquals(0, lowerHalfOfTop.group)
        assertEquals(1, lowerHalfOfTop.insert)
        val aboveTop = dragHit(
            overlayTop = 100f - height,
            bounds = bounds,
            rowHeight = height,
            canTarget = { true }
        )
        assertNull(aboveTop.group)
        assertEquals(0, aboveTop.insert)
        val hitTopWhileDraggingBottom = dragHit(
            overlayTop = 100f,
            bounds = bounds,
            rowHeight = height,
            canTarget = { true }
        )
        assertEquals(0, hitTopWhileDraggingBottom.group)
        assertEquals(0, hitTopWhileDraggingBottom.insert)
        val betweenRows = dragHit(
            overlayTop = 136f,
            bounds = bounds,
            rowHeight = height,
            canTarget = { true }
        )
        assertNull(betweenRows.group)
        assertEquals(1, betweenRows.insert)
        val majorityOnLowerRowGroupsIt = dragHit(
            overlayTop = 247f,
            bounds = mapOf(
                0 to row(87f, 168f),
                1 to row(276f, 168f),
                2 to row(465f, 168f)
            ),
            rowHeight = 168f,
            canTarget = { true }
        )
        assertEquals(1, majorityOnLowerRowGroupsIt.group)
        val alignedOnRow = dragHit(
            overlayTop = 172f,
            bounds = bounds,
            rowHeight = height,
            canTarget = { true }
        )
        assertEquals(1, alignedOnRow.group)
        assertEquals(1, alignedOnRow.insert)
    }

    @Test
    fun draggingStandaloneAppLeavesOriginFilled() {
        val app = org.mockito.Mockito.mock(su.myt.home.model.App::class.java)
        org.mockito.Mockito.`when`(app.id).thenReturn("a.pkg/Act")
        val visuals = groupVisuals(
            apps = listOf(app),
            draggingAppIndex = 0,
            insertSlot = -1
        )
        assertEquals(1, visuals.size)
        assertEquals(GroupVisual.Item(0, app), visuals[0])
    }

    @Test
    fun pinnedVisualsRetainsOriginWhenDraggingStandaloneApp() {
        val visuals = pinnedVisuals(
            count = 4,
            draggingFrom = 1,
            extracting = false,
            insertRow = 2
        )
        assertEquals(4, visuals.size)
        assertEquals(
            listOf(
                PinnedVisual.Group(0),
                PinnedVisual.Group(1),
                PinnedVisual.Group(2),
                PinnedVisual.Group(3)
            ),
            visuals
        )
    }

    @Test
    fun pinnedVisualsInsertsGapOnlyWhenExtractingFromGroup() {
        val visuals = pinnedVisuals(
            count = 3,
            draggingFrom = 0,
            extracting = true,
            insertRow = 1
        )
        assertEquals(4, visuals.size)
        assertEquals(
            listOf(
                PinnedVisual.Group(0),
                PinnedVisual.Gap,
                PinnedVisual.Group(1),
                PinnedVisual.Group(2)
            ),
            visuals
        )
    }

    @Test
    fun pinnedVisualsMovesOriginSlotWhenMovingStandaloneAppToOtherRow() {
        val visuals = pinnedVisuals(
            count = 4,
            draggingFrom = 1,
            extracting = false,
            insertRow = 3
        )
        assertEquals(4, visuals.size)
        assertEquals(
            listOf(
                PinnedVisual.Group(0),
                PinnedVisual.Group(2),
                PinnedVisual.Group(1),
                PinnedVisual.Group(3)
            ),
            visuals
        )
    }

    @Test
    fun pinnedVisualsInsertsCollapsingRowAtExactOriginPosition() {
        val visuals = pinnedVisuals(
            count = 3,
            draggingFrom = -1,
            extracting = false,
            insertRow = -1,
            collapsingRow = Pair(1, "app-b")
        )
        assertEquals(4, visuals.size)
        assertEquals(
            listOf(
                PinnedVisual.Group(0),
                PinnedVisual.CollapsingRow("app-b"),
                PinnedVisual.Group(1),
                PinnedVisual.Group(2)
            ),
            visuals
        )
    }
}
