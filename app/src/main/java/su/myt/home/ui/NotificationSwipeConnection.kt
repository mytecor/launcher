package su.myt.home.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

internal class NotificationSwipeConnection(
    private val isSearching: () -> Boolean,
    private val isDragActive: () -> Boolean,
    private val isAtTop: () -> Boolean,
    private val swipeThresholdPx: () -> Float,
    private val flingThresholdPx: () -> Float,
    private val onOpenNotifications: () -> Unit
) : NestedScrollConnection {

    var inGesture = false
        private set
    var startedAtTop = false
        private set
    var totalDragY = 0f
        private set
    var totalDragX = 0f
        private set
    var triggered = false
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput) {
            if (!inGesture) {
                inGesture = true
                startedAtTop = isAtTop() && !isSearching() && !isDragActive()
            }
            if (available.y < 0f) {
                startedAtTop = false
                totalDragY = 0f
                totalDragX = 0f
            }
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (!startedAtTop || isSearching() || isDragActive()) {
            totalDragY = 0f
            totalDragX = 0f
            return Offset.Zero
        }

        if (available.y > 0f) {
            totalDragY += available.y
            totalDragX += available.x
            val threshold = swipeThresholdPx()
            if (!triggered && totalDragY >= threshold && totalDragY > abs(totalDragX) * 1.25f) {
                triggered = true
                onOpenNotifications()
            }
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity
    ): Velocity {
        if (startedAtTop && !isSearching() && !isDragActive() && !triggered) {
            val flingThreshold = flingThresholdPx()
            if (available.y >= flingThreshold && available.y > abs(available.x) * 1.25f) {
                triggered = true
                onOpenNotifications()
            }
        }
        reset()
        return Velocity.Zero
    }

    fun reset() {
        inGesture = false
        startedAtTop = false
        triggered = false
        totalDragY = 0f
        totalDragX = 0f
    }
}
