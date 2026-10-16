package su.myt.home.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSwipeConnectionTest {

    private var openNotificationsCount = 0
    private var isSearching = false
    private var isDragActive = false
    private var isAtTop = true
    private val swipeThreshold = 100f
    private val flingThreshold = 500f

    private val connection = NotificationSwipeConnection(
        isSearching = { isSearching },
        isDragActive = { isDragActive },
        isAtTop = { isAtTop },
        swipeThresholdPx = { swipeThreshold },
        flingThresholdPx = { flingThreshold },
        onOpenNotifications = { openNotificationsCount++ }
    )

    @Test
    fun programmaticScrollDoesNotOpenNotificationsOrAccumulateDistance() {
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 150f), NestedScrollSource.SideEffect)
        assertEquals(0, openNotificationsCount)
        assertEquals(0f, connection.totalDragY)
        connection.onPostScroll(Offset.Zero, Offset(0f, 80f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
    }

    @Test
    fun dragInterruptedByAppDragCannotResumeNotificationGesture() {
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 80f), NestedScrollSource.UserInput)
        isDragActive = true
        connection.onPostScroll(Offset.Zero, Offset(0f, 1f), NestedScrollSource.UserInput)
        isDragActive = false
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 150f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
    }

    @Test
    fun swipeDownTriggersNotificationWhenGestureStartsAtTop() {
        isAtTop = true

        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        assertTrue(connection.startedAtTop)

        // Drag down by 60px (< threshold)
        connection.onPostScroll(Offset.Zero, Offset(0f, 60f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
        assertFalse(connection.triggered)

        // Drag down another 50px (total 110px >= 100px threshold)
        connection.onPostScroll(Offset.Zero, Offset(0f, 50f), NestedScrollSource.UserInput)
        assertEquals(1, openNotificationsCount)
        assertTrue(connection.triggered)

        // Further drags in the same gesture do not re-trigger
        connection.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput)
        assertEquals(1, openNotificationsCount)
    }

    @Test
    fun swipeDownDoesNotTriggerWhenGestureStartsBelowTopEvenIfItReachesTop() = runBlocking {
        // Initially NOT at top (user is scrolling to top)
        isAtTop = false

        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        assertFalse(connection.startedAtTop)

        // Suppose during this drag the list reaches top:
        isAtTop = true
        // And user continues pulling down:
        connection.onPostScroll(Offset.Zero, Offset(0f, 200f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
        assertFalse(connection.triggered)

        // Fling in the same gesture also does not trigger
        connection.onPostFling(Velocity.Zero, Velocity(0f, 800f))
        assertEquals(0, openNotificationsCount)
        assertFalse(connection.inGesture)

        // Now that finger was lifted and list is at the top, a NEW gesture starts:
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        assertTrue(connection.startedAtTop)

        connection.onPostScroll(Offset.Zero, Offset(0f, 120f), NestedScrollSource.UserInput)
        assertEquals(1, openNotificationsCount)
    }

    @Test
    fun dragUpFromTopDisablesShadeTrigger() {
        isAtTop = true
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        assertTrue(connection.startedAtTop)

        // User reverses direction and drags up (scrolling down into list)
        connection.onPreScroll(Offset(0f, -20f), NestedScrollSource.UserInput)
        assertFalse(connection.startedAtTop)
        assertEquals(0f, connection.totalDragY)

        // User pulls down again in the same drag: should not trigger
        connection.onPostScroll(Offset.Zero, Offset(0f, 150f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
    }

    @Test
    fun swipeDownDoesNotTriggerWhenSearching() {
        isSearching = true
        isAtTop = true
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 150f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
        assertFalse(connection.triggered)
    }

    @Test
    fun swipeDownDoesNotTriggerWhenDraggingApp() {
        isDragActive = true
        isAtTop = true
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 150f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
        assertFalse(connection.triggered)
    }

    @Test
    fun horizontalSwipeDoesNotTrigger() {
        isAtTop = true
        connection.onPreScroll(Offset(10f, 5f), NestedScrollSource.UserInput)

        // User moves predominantly horizontally (e.g. 150px X, 80px Y)
        connection.onPostScroll(Offset.Zero, Offset(150f, 80f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)
    }

    @Test
    fun downwardFlingTriggersNotificationOnlyWhenStartedAtTop() = runBlocking {
        isAtTop = true
        connection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
        assertEquals(0, openNotificationsCount)

        connection.onPostFling(Velocity.Zero, Velocity(0f, 600f))
        assertEquals(1, openNotificationsCount)
    }
}
