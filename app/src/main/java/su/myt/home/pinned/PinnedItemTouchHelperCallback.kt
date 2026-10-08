package su.myt.home.pinned

import android.graphics.Canvas
import android.graphics.Color
import android.view.ViewOutlineProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class PinnedItemTouchHelperCallback(
    private val isDragEnabled: () -> Boolean,
    private val onMoveItem: (from: Int, to: Int) -> Boolean,
    private val onDragFinished: () -> Unit
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

    override fun isLongPressDragEnabled(): Boolean = isDragEnabled()

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        return onMoveItem(from, to)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.apply {
                outlineAmbientShadowColor = Color.TRANSPARENT
                outlineSpotShadowColor = Color.TRANSPARENT
                outlineProvider = null
                translationZ = 1f
            }
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        viewHolder.itemView.translationX = dX
        viewHolder.itemView.translationY = dY
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.apply {
            translationX = 0f
            translationY = 0f
            translationZ = 0f
            elevation = 0f
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        onDragFinished()
    }
}
