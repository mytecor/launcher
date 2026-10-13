package su.myt.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import su.myt.home.model.App

class ItemDragSession(
    private val context: Context,
    private val area: FrameLayout,
    private val list: RecyclerView,
    private val dp: (Int) -> Int,
    private val resolveIcon: (App, ItemMorphView) -> Drawable
) {
    data class Morph(
        val view: ItemMorphView,
        val startX: Float,
        val startY: Float,
        val startW: Float,
        var endX: Float,
        var endY: Float,
        val endW: Float,
        val expanding: Boolean
    )

    var overlay: ItemMorphView? = null
        private set
    var morphProgress = 0f
        private set
    var grouping = false
        private set
    var hover: AppAdapter.ViewHolder? = null
        private set
    var sourceBubbleW = 0f
        private set

    private var morphAnimator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null
    private var lastAreaX = 0f
    private var lastAreaY = 0f
    private val liveViews = mutableListOf<ItemMorphView>()

    fun areaLoc(): IntArray = IntArray(2).also { area.getLocationOnScreen(it) }

    fun screenToAreaX(screenX: Float): Float = screenX - areaLoc()[0]
    fun screenToAreaY(screenY: Float): Float = screenY - areaLoc()[1]

    fun morphView(app: App, widthPx: Int, zDp: Int = 16): ItemMorphView {
        return ItemMorphView(context).apply {
            bind(resolveIcon(app, this), app.label)
            translationZ = dp(zDp).toFloat()
            area.addView(this, FrameLayout.LayoutParams(widthPx, dp(64)))
            liveViews.add(this)
        }
    }

    fun ensureOverlay(app: App, bubbleView: View, asRow: Boolean) {
        if (overlay != null) return
        sourceBubbleW = bubbleView.width.toFloat().takeIf { it > 0 } ?: dp(80).toFloat()
        val startW = if (asRow) fullRowW() else sourceBubbleW
        morphProgress = if (asRow) 1f else 0f
        grouping = false
        overlay = morphView(app, startW.toInt(), zDp = 24)
        bubbleView.alpha = 0f
        if (!asRow) {
            animateProgress(1f)
        }
    }

    fun ensureOverlayFromRow(app: App, itemView: View) {
        if (overlay != null) return
        sourceBubbleW = (itemView.width.toFloat() - dp(8)) / 2f
        morphProgress = 1f
        grouping = false
        overlay = morphView(app, itemView.width, zDp = 24)
        itemView.alpha = 0f
        moveToViewCenter(itemView)
    }

    fun moveFinger(rawX: Float, rawY: Float) {
        lastAreaX = screenToAreaX(rawX)
        lastAreaY = screenToAreaY(rawY)
        positionOverlay()
    }

    fun moveToViewCenter(itemView: View) {
        val loc = IntArray(2)
        itemView.getLocationOnScreen(loc)
        lastAreaX = screenToAreaX(loc[0] + itemView.width / 2f)
        lastAreaY = screenToAreaY(loc[1] + itemView.height / 2f)
        positionOverlay()
    }

    fun setGrouping(next: Boolean, target: AppAdapter.ViewHolder?) {
        if (target != hover) {
            hover?.clearGroupHighlightInstant()
            hover = target
            target?.setGroupHighlight(true)
        }
        if (next != grouping) {
            grouping = next
            animateProgress(if (next) 0f else 1f)
        } else if (morphAnimator?.isRunning != true) {
            positionOverlay()
        }
    }

    fun groupingTarget(
        draggedCenterY: Float,
        fromPos: Int,
        canGroup: (Int) -> Boolean
    ): AppAdapter.ViewHolder? {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val holder = list.getChildViewHolder(child) as? AppAdapter.ViewHolder ?: continue
            val toPos = holder.bindingAdapterPosition
            if (toPos == RecyclerView.NO_POSITION || fromPos == RecyclerView.NO_POSITION || toPos == fromPos) continue
            val distY = kotlin.math.abs(draggedCenterY - (child.top + child.height / 2f))
            val threshold = child.height * if (hover == holder) 0.40f else 0.25f
            if (distY <= threshold && canGroup(toPos)) return holder
        }
        return null
    }

    fun insertPos(listX: Float, listY: Float): Int {
        val child = list.findChildViewUnder(listX, listY)
        if (child == null) {
            return if (listY < list.height / 2f) 0 else (list.adapter?.itemCount ?: 0)
        }
        val pos = list.getChildViewHolder(child)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
        if (pos == RecyclerView.NO_POSITION) {
            return if (listY < list.height / 2f) 0 else (list.adapter?.itemCount ?: 0)
        }
        val childCenterY = child.top + child.height / 2f
        return if (listY < childCenterY) pos else pos + 1
    }

    fun takeOverlay(): ItemMorphView? {
        morphAnimator?.cancel()
        morphAnimator = null
        val view = overlay
        overlay = null
        sourceBubbleW = 0f
        grouping = false
        return view
    }

    fun play(morphs: List<Morph>, onEnd: () -> Unit) {
        settleAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SETTLE_MS
            interpolator = EASE
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                for (m in morphs) {
                    m.view.updateProgress(
                        progress = progress,
                        startX = m.startX,
                        startY = m.startY,
                        startW = m.startW,
                        endX = m.endX,
                        endY = m.endY,
                        endW = m.endW,
                        isExpanding = m.expanding
                    )
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    settleAnimator = null
                    for (m in morphs) {
                        area.removeView(m.view)
                        liveViews.remove(m.view)
                    }
                    if (!cancelled) onEnd()
                }
            })
        }
        settleAnimator = anim
        anim.start()
    }

    fun cancel(restore: View? = null) {
        morphAnimator?.cancel()
        morphAnimator = null
        settleAnimator?.cancel()
        settleAnimator = null
        overlay?.let { area.removeView(it) }
        overlay = null
        for (view in liveViews) area.removeView(view)
        liveViews.clear()
        sourceBubbleW = 0f
        grouping = false
        hover?.clearGroupHighlightInstant()
        hover = null
        restore?.alpha = 1f
    }

    fun clearHover() {
        hover?.clearGroupHighlightInstant()
        hover = null
        grouping = false
    }

    private fun fullRowW() = (list.width - list.paddingLeft - list.paddingRight).toFloat()

    private fun positionOverlay() {
        val view = overlay ?: return
        view.updateDragState(
            areaX = lastAreaX,
            areaY = lastAreaY,
            morphProgress = morphProgress,
            targetBubbleW = hover?.computeBubbleWidthInRow()?.takeIf { it > 0 } ?: sourceBubbleW,
            fullRowW = fullRowW(),
            listPaddingLeft = list.paddingLeft.toFloat()
        )
    }

    private fun animateProgress(target: Float) {
        morphAnimator?.cancel()
        morphAnimator = ValueAnimator.ofFloat(morphProgress, target).apply {
            duration = MORPH_MS
            interpolator = EASE
            addUpdateListener { anim ->
                morphProgress = anim.animatedValue as Float
                positionOverlay()
            }
            start()
        }
    }

    companion object {
        const val SETTLE_MS = 240L
        const val MORPH_MS = 180L
        private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
