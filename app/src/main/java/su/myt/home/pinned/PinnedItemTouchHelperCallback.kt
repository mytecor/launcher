package su.myt.home.pinned

import android.graphics.Canvas
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.ViewOutlineProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import su.myt.home.ui.AppAdapter

class PinnedItemTouchHelperCallback(
    private val isDragEnabled: () -> Boolean,
    private val findGroupTarget: (draggedCenterY: Float, fromPos: Int) -> AppAdapter.ViewHolder?,
    private val onGroupItems: (from: Int, to: Int) -> Unit,
    private val onMoveItem: (from: Int, to: Int) -> Boolean,
    private val onDragStarted: (holder: RecyclerView.ViewHolder) -> Unit,
    private val onDragHover: (holder: RecyclerView.ViewHolder, target: AppAdapter.ViewHolder?) -> Unit,
    private val onDragFinished: (holder: RecyclerView.ViewHolder?) -> Unit
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
    0
) {
    private var lastDraggedHolder: RecyclerView.ViewHolder? = null
    private var lastDraggedPosition: Int = RecyclerView.NO_POSITION
    private var pendingGroupTarget: AppAdapter.ViewHolder? = null
    private var isGroupingPending = false
    private var isFingerDown = false
    private var lastActionWasUp = false

    fun onFingerDown() {
        isFingerDown = true
        lastActionWasUp = false
    }

    fun onFingerUp() {
        isFingerDown = false
        lastActionWasUp = true
    }

    fun onFingerCancel() {
        isFingerDown = false
        lastActionWasUp = false
    }

    override fun isLongPressDragEnabled(): Boolean = isDragEnabled()

    override fun getAnimationDuration(
        recyclerView: RecyclerView,
        animationType: Int,
        animateDx: Float,
        animateDy: Float
    ): Long {
        if (isGroupingPending && animationType == ItemTouchHelper.ANIMATION_TYPE_DRAG) return 0L
        return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            isFingerDown = true
            lastActionWasUp = false
            lastDraggedHolder = viewHolder
            lastDraggedPosition = viewHolder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            pendingGroupTarget = null
            isGroupingPending = false
            viewHolder?.itemView?.apply {
                isPressed = false
                jumpDrawablesToCurrentState()
                (background as? android.graphics.drawable.RippleDrawable)?.let { ripple ->
                    ripple.state = intArrayOf()
                    ripple.jumpToCurrentState()
                }
                outlineAmbientShadowColor = Color.TRANSPARENT
                outlineSpotShadowColor = Color.TRANSPARENT
                outlineProvider = null
                translationZ = 4f
            }
            viewHolder?.let(onDragStarted)
        } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            val target = pendingGroupTarget
            val fromPos = lastDraggedPosition
            val toPos = target?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            val draggedHolder = lastDraggedHolder
            pendingGroupTarget = null
            target?.clearGroupHighlightInstant()

            val canPerformGroup = isGroupingPending &&
                (lastActionWasUp || !isFingerDown) &&
                target != null &&
                fromPos != RecyclerView.NO_POSITION &&
                toPos != RecyclerView.NO_POSITION &&
                fromPos != toPos

            if (canPerformGroup) {
                isGroupingPending = false
                target!!.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                draggedHolder?.itemView?.visibility = android.view.View.INVISIBLE
                draggedHolder?.itemView?.apply {
                    translationX = 0f
                    translationY = 0f
                    translationZ = 0f
                }
                onGroupItems(fromPos, toPos)
            } else {
                isGroupingPending = false
                onDragFinished(draggedHolder)
            }
            lastDraggedHolder = null
            lastDraggedPosition = RecyclerView.NO_POSITION
            lastActionWasUp = false
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        if (isGroupingPending) return false
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        val moved = onMoveItem(from, to)
        if (moved) lastDraggedPosition = to
        return moved
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) return

        val fromPos = viewHolder.bindingAdapterPosition
        val draggedCenterY = viewHolder.itemView.top + dY + viewHolder.itemView.height / 2f
        val found = findGroupTarget(draggedCenterY, fromPos)
        if (found != pendingGroupTarget) {
            pendingGroupTarget?.clearGroupHighlightInstant()
            pendingGroupTarget = found
            isGroupingPending = found != null
        }
        onDragHover(viewHolder, found)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.apply {
            isPressed = false
            jumpDrawablesToCurrentState()
            (background as? android.graphics.drawable.RippleDrawable)?.let { ripple ->
                ripple.state = intArrayOf()
                ripple.jumpToCurrentState()
            }
            translationX = 0f
            translationY = 0f
            translationZ = 0f
            elevation = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }
}
