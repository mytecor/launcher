package su.myt.home.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragHitTestTest {

    private val tops = listOf(100f, 172f, 244f, 316f)
    private val stride = 72f
    private val height = 64f

    private fun row(top: Float, h: Float = height) = RowBounds(top, h)

    @Test
    fun moveSlotFollowsOverlayTopNotFinger() {
        assertEquals(2, closestMoveSlot(244f, tops, stride))
        assertEquals(1, closestMoveSlot(172f, tops, stride))
        assertEquals(0, closestMoveSlot(100f, tops, stride))
        assertEquals(3, closestMoveSlot(316f, tops, stride))
        assertEquals(1, closestMoveSlot(200f, tops, stride))
        assertEquals(2, closestMoveSlot(220f, tops, stride))
    }

    @Test
    fun moveSlotStaysAtOriginWhenNeighborWouldHaveShiftedUnderOverlay() {
        assertEquals(2, closestMoveSlot(244f + 16f, tops, stride))
    }

    @Test
    fun moveSlotAfterLastRowWhenOverlayIsBelowAll() {
        val remaining = listOf(100f, 172f)
        assertEquals(2, closestMoveSlot(172f + stride, remaining, stride))
        assertEquals(2, insertRowSlot(172f + 40f, remaining, extracting = false, stride = stride))
        assertEquals(1, closestMoveSlot(172f, remaining, stride))
        assertEquals(
            2,
            stickyInsertSlot(
                current = 1,
                overlayTop = 172f + 40f,
                tops = remaining,
                extracting = false,
                stride = stride,
                rowHeight = height
            )
        )
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
    fun extractSlotUsesPredictedGapAboveBottomAnchor() {
        val groupAndRow = listOf(100f, 172f)
        assertEquals(0, closestExtractSlot(100f - stride, groupAndRow, stride))
        assertEquals(1, closestExtractSlot(100f, groupAndRow, stride))
        assertEquals(2, closestExtractSlot(172f, groupAndRow, stride))
    }

    @Test
    fun extractDestinationClampsAndFallsBackBelowGroup() {
        assertEquals(0, extractDestination(0, 0, 2))
        assertEquals(2, extractDestination(2, 0, 2))
        assertEquals(1, extractDestination(-1, 0, 2))
    }

    @Test
    fun insertRowSlotSwitchesExtractMode() {
        assertEquals(1, insertRowSlot(172f, tops, extracting = false, stride = stride))
        assertEquals(2, insertRowSlot(172f, listOf(100f, 172f), extracting = true, stride = stride))
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
    fun groupCandidateUsesLiveRowOverlap() {
        val live = mapOf(
            0 to row(100f),
            1 to row(172f),
            3 to row(316f)
        )
        assertEquals(
            1,
            groupCandidate(
                overlayTop = 172f,
                from = 2,
                extracting = false,
                bounds = live,
                rowHeight = height,
                current = -1,
                canTarget = { true }
            )
        )
        assertNull(
            groupCandidate(
                overlayTop = 136f,
                from = 2,
                extracting = false,
                bounds = live,
                rowHeight = height,
                current = -1,
                canTarget = { true }
            )
        )
        assertNull(
            groupCandidate(
                overlayTop = 100f,
                from = 0,
                extracting = false,
                bounds = live,
                rowHeight = height,
                current = -1,
                canTarget = { true }
            )
        )
        assertEquals(
            0,
            groupCandidate(
                overlayTop = 100f,
                from = 0,
                extracting = true,
                bounds = mapOf(0 to row(100f), 1 to row(172f)),
                rowHeight = height,
                current = -1,
                canTarget = { true }
            )
        )
        assertEquals(
            2,
            groupCandidate(
                overlayTop = 316f - 8f,
                from = 1,
                extracting = true,
                bounds = mapOf(0 to row(100f), 1 to row(172f), 2 to row(316f)),
                rowHeight = height,
                current = 2,
                canTarget = { true }
            )
        )
        assertNull(
            groupCandidate(
                overlayTop = 172f - 20f,
                from = 0,
                extracting = false,
                bounds = mapOf(0 to row(100f), 1 to row(172f), 2 to row(244f)),
                rowHeight = height,
                current = -1,
                canTarget = { true }
            )
        )
    }

    @Test
    fun stickyInsertIgnoresSmallOscillation() {
        val stuck = stickyInsertSlot(
            current = 2,
            overlayTop = 230f,
            tops = tops,
            extracting = false,
            stride = stride,
            rowHeight = height
        )
        assertEquals(2, stuck)
        val switched = stickyInsertSlot(
            current = 2,
            overlayTop = 172f,
            tops = tops,
            extracting = false,
            stride = stride,
            rowHeight = height
        )
        assertEquals(1, switched)
    }

    @Test
    fun dragHitTreatsCenterOnTopRowAsGroupNotBelow() {
        val packed = mapOf(0 to row(100f), 1 to row(172f), 2 to row(244f))
        val onTop = dragHit(
            overlayTop = 100f,
            packed = packed,
            rowHeight = height,
            currentGroup = -1,
            canTarget = { true }
        )
        assertEquals(0, onTop.group)
        assertEquals(0, onTop.insert)
        val lowerHalfOfTop = dragHit(
            overlayTop = 130f,
            packed = packed,
            rowHeight = height,
            currentGroup = -1,
            canTarget = { true }
        )
        assertEquals(0, lowerHalfOfTop.group)
        assertEquals(1, lowerHalfOfTop.insert)
        val aboveTop = dragHit(
            overlayTop = 100f - height,
            packed = packed,
            rowHeight = height,
            currentGroup = -1,
            canTarget = { true }
        )
        assertNull(aboveTop.group)
        assertEquals(0, aboveTop.insert)
        val skippedBottom = packedRowBounds(
            frozen = packed,
            from = 2,
            extracting = false,
            stride = stride
        )
        val hitTopWhileDraggingBottom = dragHit(
            overlayTop = 100f,
            packed = skippedBottom,
            rowHeight = height,
            currentGroup = -1,
            canTarget = { true }
        )
        assertEquals(0, hitTopWhileDraggingBottom.group)
        assertEquals(0, hitTopWhileDraggingBottom.insert)
        val betweenRows = dragHit(
            overlayTop = 136f,
            packed = packed,
            rowHeight = height,
            currentGroup = -1,
            canTarget = { true }
        )
        assertNull(betweenRows.group)
        assertEquals(1, betweenRows.insert)
        val majorityOnLowerRowGroupsIt = dragHit(
            overlayTop = 247f,
            packed = mapOf(
                0 to row(87f, 168f),
                1 to row(276f, 168f),
                2 to row(465f, 168f)
            ),
            rowHeight = 168f,
            currentGroup = -1,
            canTarget = { true }
        )
        assertEquals(1, majorityOnLowerRowGroupsIt.group)
        val alignedOnRowKeepsGroupEvenIfCurrentWasOther = dragHit(
            overlayTop = 172f,
            packed = packed,
            rowHeight = height,
            currentGroup = 0,
            canTarget = { true }
        )
        assertEquals(1, alignedOnRowKeepsGroupEvenIfCurrentWasOther.group)
        assertEquals(1, alignedOnRowKeepsGroupEvenIfCurrentWasOther.insert)
    }

    @Test
    fun orderedTopsFollowIndexNotHashOrder() {
        val bounds = mapOf(
            2 to row(244f),
            0 to row(100f),
            1 to row(172f)
        )
        assertEquals(listOf(100f, 172f, 244f), orderedTops(bounds))
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
}
