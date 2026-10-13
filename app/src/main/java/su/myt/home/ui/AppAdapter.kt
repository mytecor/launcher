package su.myt.home.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import su.myt.home.R
import su.myt.home.model.App
import su.myt.home.model.AppGroup

data class OtherAppMorphInfo(
    val app: App,
    val startX: Float,
    val startY: Float,
    val startW: Float
)

class AppAdapter(
    private val context: Context,
    private val isItemPinned: (App) -> Boolean,
    private val shouldShowStar: () -> Boolean,
    private val loadIcon: ((App, (Drawable) -> Unit) -> Drawable?)? = null,
    private val onItemClick: (App) -> Unit,
    private val onItemLongClick: (app: App, groupIndex: Int, appIndex: Int, rawX: Float, rawY: Float) -> Boolean,
    private val onPullOutFromGroup: (
        groupIndex: Int,
        appIndex: Int,
        dropRawX: Float,
        dropRawY: Float,
        startX: Float,
        startY: Float,
        startW: Float,
        app: App,
        otherAppInfo: OtherAppMorphInfo?
    ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    private val onBubbleDragMove: (
        rawX: Float,
        rawY: Float,
        app: App,
        sourceGroupPos: Int,
        bubbleIndex: Int,
        bubbleView: View
    ) -> Unit = { _, _, _, _, _, _ -> },
    private val onBubbleDragCancel: (bubbleView: View?) -> Unit = {},
    private val onDragGroupFinished: () -> Unit = {}
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_SINGLE = 0
        const val VIEW_TYPE_GROUP = 1
    }

    var items: MutableList<AppGroup> = mutableListOf()
    var hiddenRowPosition: Int = -1
    var hiddenRowPosition2: Int = -1
    var hiddenBubbleGroupIndex: Int = -1
    var hiddenBubbleIndex: Int = -1

    private val defaultIcon: Drawable by lazy {
        context.packageManager.defaultActivityIcon
    }

    abstract class ViewHolder(open val container: View) : RecyclerView.ViewHolder(container) {
        abstract fun setGroupHighlight(highlight: Boolean)
        abstract fun computeBubbleWidthInRow(): Float
        open fun clearGroupHighlightInstant() {}
    }

    fun resetDragChrome(list: RecyclerView) {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val holder = list.getChildViewHolder(child) as? ViewHolder ?: continue
            holder.clearGroupHighlightInstant()
        }
    }

    inner class SingleViewHolder(
        override val container: LinearLayout,
        val row: LinearLayout,
        val iconView: ImageView,
        val labelView: TextView,
        val starView: ImageView,
        val surface: GradientDrawable,
        val placeholderSlot: FrameLayout
    ) : ViewHolder(container) {
        var boundAppId: String? = null
        var isSlotFreed: Boolean = false
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        init {
            row.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                false
            }
            row.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                    items[pos].apps.firstOrNull()?.let(onItemClick)
                }
            }
            row.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                    val app = items[pos].apps.firstOrNull() ?: return@setOnLongClickListener false
                    if (shouldShowStar()) {
                        onItemLongClick(app, pos, 0, lastTouchX, lastTouchY)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }

        override fun computeBubbleWidthInRow(): Float {
            val totalW = if (container.width > 0) container.width.toFloat() else (row.width.toFloat().takeIf { it > 0 } ?: 0f)
            return if (totalW > 0f) (totalW - dp(8)) / 2f else 0f
        }

        private fun cancelHighlightAnims() {
            android.transition.TransitionManager.endTransitions(container)
            labelView.animate().cancel()
            starView.animate().cancel()
            iconView.animate().cancel()
        }

        override fun setGroupHighlight(highlight: Boolean) {
            if (highlight == isSlotFreed) return
            cancelHighlightAnims()
            isSlotFreed = highlight
            if (highlight) {
                placeholderSlot.visibility = View.VISIBLE
                labelView.animate().alpha(0f).setDuration(120).start()
                starView.animate().alpha(0f).setDuration(120).start()
                val targetBubbleW = computeBubbleWidthInRow()
                val targetTransX = if (targetBubbleW > 0f) (targetBubbleW - dp(36)) / 2f - dp(16) else dp(36).toFloat()
                iconView.animate().translationX(targetTransX).setDuration(150).start()
            } else {
                placeholderSlot.visibility = View.GONE
                labelView.animate().alpha(1f).setDuration(150).start()
                starView.animate().alpha(1f).setDuration(150).start()
                iconView.animate().translationX(0f).setDuration(150).start()
            }
        }

        override fun clearGroupHighlightInstant() {
            cancelHighlightAnims()
            isSlotFreed = false
            placeholderSlot.visibility = View.GONE
            labelView.alpha = 1f
            starView.alpha = 1f
            iconView.translationX = 0f
        }
    }

    inner class GroupViewHolder(
        val row: LinearLayout
    ) : ViewHolder(row) {
        val bubbleSurfaces = mutableListOf<GradientDrawable>()
        val boundAppIds = mutableListOf<String>()
        var isSlotFreed: Boolean = false
        var placeholderSlot: View? = null

        override fun computeBubbleWidthInRow(): Float {
            val totalW = row.width.toFloat()
            val appsCount = row.childCount - (if (isSlotFreed) 1 else 0)
            val newCount = (appsCount + 1).coerceAtMost(4)
            if (totalW <= 0f || newCount <= 0) return 0f
            val totalGaps = (newCount - 1) * dp(8)
            return (totalW - totalGaps) / newCount
        }

        private fun cancelHighlightAnims() {
            android.transition.TransitionManager.endTransitions(row)
            for (i in 0 until row.childCount) {
                row.getChildAt(i).animate().cancel()
            }
        }

        override fun setGroupHighlight(highlight: Boolean) {
            if (highlight == isSlotFreed) return
            val currentAppsCount = row.childCount - (if (isSlotFreed) 1 else 0)
            if (highlight && currentAppsCount >= 4) return

            cancelHighlightAnims()
            isSlotFreed = highlight

            if (highlight) {
                val slot = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                        leftMargin = dp(8)
                    }
                    background = GradientDrawable().apply {
                        setColor(context.getColor(R.color.launcher_row))
                        alpha = 76
                        cornerRadius = dp(20).toFloat()
                    }
                }
                placeholderSlot = slot
                row.addView(slot)
            } else {
                placeholderSlot?.let { slot ->
                    row.removeView(slot)
                    placeholderSlot = null
                }
            }
        }

        override fun clearGroupHighlightInstant() {
            cancelHighlightAnims()
            isSlotFreed = false
            placeholderSlot?.let { slot ->
                row.removeView(slot)
                placeholderSlot = null
            }
            for (i in 0 until row.childCount) {
                row.getChildAt(i).apply {
                    translationX = 0f
                    translationY = 0f
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isGroup) VIEW_TYPE_GROUP else VIEW_TYPE_SINGLE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == VIEW_TYPE_GROUP) {
            val row = LinearLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                isBaselineAligned = false
                clipChildren = false
                clipToPadding = false
                background = null
            }
            return GroupViewHolder(row)
        } else {
            val iconView = ImageView(context)
            val labelView = TextView(context).apply {
                setTextColor(context.getColor(R.color.launcher_foreground))
                textSize = 19f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val starView = ImageView(context).apply {
                setImageResource(R.drawable.ic_star)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.launcher_accent))
                scaleType = ImageView.ScaleType.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val surface = GradientDrawable().apply {
                setColor(context.getColor(R.color.launcher_row))
                cornerRadius = dp(20).toFloat()
            }
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            val placeholderSlot = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    leftMargin = dp(8)
                }
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.launcher_row))
                    alpha = 76
                    cornerRadius = dp(20).toFloat()
                }
                visibility = View.GONE
            }
            val row = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(64),
                    1f
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
                clipChildren = false
                clipToPadding = false
                background = RippleDrawable(
                    ColorStateList.valueOf((context.getColor(R.color.launcher_accent) and 0x00FFFFFF) or 0x24000000),
                    surface, mask
                )
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(labelView, LinearLayout.LayoutParams(0, -2, 1f))
                addView(starView, LinearLayout.LayoutParams(dp(36), dp(36)))
            }
            val container = LinearLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                clipChildren = false
                clipToPadding = false
                addView(row)
                addView(placeholderSlot)
            }
            return SingleViewHolder(container, row, iconView, labelView, starView, surface, placeholderSlot)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = items[position]
        if (holder is SingleViewHolder) {
            val app = group.apps.firstOrNull() ?: return
            holder.boundAppId = app.id
            val isHidden = (position == hiddenRowPosition || position == hiddenRowPosition2)
            holder.container.visibility = View.VISIBLE
            holder.container.alpha = if (isHidden) 0f else 1.0f
            holder.container.scaleX = 1.0f
            holder.container.scaleY = 1.0f
            holder.container.translationX = 0f
            holder.container.translationY = 0f
            holder.clearGroupHighlightInstant()

            val icon = app.icon ?: loadIcon?.invoke(app) { loadedDrawable ->
                if (holder.boundAppId == app.id) {
                    holder.iconView.setImageDrawable(loadedDrawable)
                }
            }
            holder.iconView.setImageDrawable(icon ?: defaultIcon)
            holder.labelView.text = app.label
            val pinned = isItemPinned(app)
            holder.starView.visibility = if (shouldShowStar() && pinned) View.VISIBLE else View.GONE
            holder.row.contentDescription = if (pinned) {
                context.getString(R.string.pinned_description, app.badgedLabel)
            } else {
                app.badgedLabel
            }
        } else if (holder is GroupViewHolder) {
            val isGroupHidden = (position == hiddenRowPosition || position == hiddenRowPosition2 ||
                (position == hiddenBubbleGroupIndex && hiddenBubbleIndex == -2))
            holder.row.visibility = View.VISIBLE
            holder.row.alpha = if (isGroupHidden) 0f else 1.0f
            holder.row.scaleX = 1.0f
            holder.row.scaleY = 1.0f
            holder.row.translationX = 0f
            holder.row.translationY = 0f
            holder.clearGroupHighlightInstant()

            val newChildCount = group.apps.size

            val newAppIds = group.apps.map { it.id }.toSet()
            var childIdx = 0
            while (childIdx < holder.boundAppIds.size && holder.row.childCount > newChildCount) {
                val appId = holder.boundAppIds[childIdx]
                if (appId !in newAppIds) {
                    holder.row.removeViewAt(childIdx)
                    holder.boundAppIds.removeAt(childIdx)
                    if (childIdx < holder.bubbleSurfaces.size) {
                        holder.bubbleSurfaces.removeAt(childIdx)
                    }
                } else {
                    childIdx++
                }
            }
            while (holder.row.childCount > newChildCount) {
                val last = holder.row.childCount - 1
                holder.row.removeViewAt(last)
                if (last < holder.boundAppIds.size) holder.boundAppIds.removeAt(last)
                if (last < holder.bubbleSurfaces.size) holder.bubbleSurfaces.removeAt(last)
            }

            for (i in group.apps.indices) {
                val app = group.apps[i]
                if (i < holder.boundAppIds.size) {
                    holder.boundAppIds[i] = app.id
                } else {
                    holder.boundAppIds.add(app.id)
                }
                val isExisting = i < holder.row.childCount
                val surface = if (isExisting && i < holder.bubbleSurfaces.size) {
                    holder.bubbleSurfaces[i]
                } else {
                    GradientDrawable().apply {
                        setColor(context.getColor(R.color.launcher_row))
                        cornerRadius = dp(20).toFloat()
                    }.also { holder.bubbleSurfaces.add(it) }
                }
                val mask = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(20).toFloat()
                }

                var downRawX = 0f
                var downRawY = 0f
                var isDragging = false
                var isExtracted = false
                var currentTargetSlot = i

                lateinit var bubble: FrameLayout

                fun clearBubbleRipple(target: View) {
                    target.isPressed = false
                    target.jumpDrawablesToCurrentState()
                    (target.background as? RippleDrawable)?.let { ripple ->
                        ripple.state = intArrayOf()
                        ripple.jumpToCurrentState()
                    }
                }

                fun startDragBubble() {
                    isDragging = true
                    isExtracted = false
                    currentTargetSlot = i
                    clearBubbleRipple(bubble)
                    bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    bubble.translationZ = dp(12).toFloat()
                    holder.row.translationZ = dp(6).toFloat()
                    bubble.parent?.requestDisallowInterceptTouchEvent(true)
                    holder.row.requestDisallowInterceptTouchEvent(true)
                }

                val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onLongPress(e: MotionEvent) {
                        if (shouldShowStar()) {
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                onItemLongClick(app, pos, i, downRawX, downRawY)
                            }
                        } else {
                            startDragBubble()
                        }
                    }
                })

                bubble = if (isExisting) {
                    (holder.row.getChildAt(i) as FrameLayout).apply {
                        (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            val margin = if (i == 0) 0 else dp(8)
                            if (lp.leftMargin != margin || lp.weight != 1f) {
                                lp.leftMargin = margin
                                lp.weight = 1f
                                layoutParams = lp
                            }
                        }
                        translationX = 0f
                        translationY = 0f
                        scaleX = 1f
                        scaleY = 1f
                    }
                } else {
                    FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                            setMargins(if (i == 0) 0 else dp(8), 0, 0, 0)
                        }
                        background = RippleDrawable(
                            ColorStateList.valueOf((context.getColor(R.color.launcher_accent) and 0x00FFFFFF) or 0x24000000),
                            surface, mask
                        )
                    }
                }
                bubble.apply {
                    contentDescription = app.badgedLabel
                    setOnLongClickListener {
                        if (shouldShowStar()) {
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                onItemLongClick(app, pos, i, downRawX, downRawY)
                            }
                        } else {
                            startDragBubble()
                        }
                        true
                    }
                    setOnTouchListener { v, event ->
                        gestureDetector.onTouchEvent(event)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downRawX = event.rawX
                                downRawY = event.rawY
                                isDragging = false
                                isExtracted = false
                                currentTargetSlot = i
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (isDragging) {
                                    clearBubbleRipple(v)
                                    val dX = event.rawX - downRawX
                                    val dY = event.rawY - downRawY

                                    val pullOutThreshold = dp(36)
                                    val currentlyExtracted = kotlin.math.abs(dY) > pullOutThreshold

                                    if (currentlyExtracted != isExtracted) {
                                        isExtracted = currentlyExtracted
                                        val slotStep = if (holder.row.childCount > 1) {
                                            (holder.row.getChildAt(1).left - holder.row.getChildAt(0).left).toFloat()
                                        } else 0f

                                        if (isExtracted) {
                                            v.alpha = 0f
                                            v.translationX = 0f
                                            v.translationY = 0f
                                            // Close gap in the remaining bubbles
                                            for (j in 0 until holder.row.childCount) {
                                                if (j == i) continue
                                                val targetTransX = if (j > i) -slotStep else 0f
                                                holder.row.getChildAt(j).animate().translationX(targetTransX).setDuration(150).start()
                                            }
                                        } else {
                                            v.alpha = 1f
                                            v.translationX = dX
                                            v.translationY = dY
                                            v.scaleX = 1.0f
                                            v.scaleY = 1.0f
                                            v.translationZ = dp(12).toFloat()
                                            // Restore in-group slot positions
                                            for (j in 0 until holder.row.childCount) {
                                                if (j == i) continue
                                                val targetTransX = when {
                                                    currentTargetSlot > i && j in (i + 1)..currentTargetSlot -> -slotStep
                                                    currentTargetSlot < i && j in currentTargetSlot..(i - 1) -> slotStep
                                                    else -> 0f
                                                }
                                                holder.row.getChildAt(j).animate().translationX(targetTransX).setDuration(150).start()
                                            }
                                            onBubbleDragCancel(v)
                                        }
                                    }

                                    if (isExtracted) {
                                        val pos = holder.bindingAdapterPosition
                                        if (pos != RecyclerView.NO_POSITION) {
                                            onBubbleDragMove(event.rawX, event.rawY, app, pos, i, v)
                                        }
                                    } else {
                                        // Follow finger 1:1 with zero delay inside group
                                        v.translationX = dX
                                        v.translationY = dY
                                        val slotStep = if (holder.row.childCount > 1) {
                                            val diff = (holder.row.getChildAt(1).left - holder.row.getChildAt(0).left).toFloat()
                                            if (diff > 0f) diff else (v.width + dp(8)).toFloat()
                                        } else 0f

                                        if (slotStep > 0f) {
                                            val offsetSlots = kotlin.math.round(dX / slotStep).toInt()
                                            val newTargetSlot = (i + offsetSlots).coerceIn(0, holder.row.childCount - 1)

                                            if (newTargetSlot != currentTargetSlot) {
                                                currentTargetSlot = newTargetSlot
                                                for (j in 0 until holder.row.childCount) {
                                                    if (j == i) continue
                                                    val targetTransX = when {
                                                        currentTargetSlot > i && j in (i + 1)..currentTargetSlot -> -slotStep
                                                        currentTargetSlot < i && j in currentTargetSlot..(i - 1) -> slotStep
                                                        else -> 0f
                                                    }
                                                    holder.row.getChildAt(j).animate()
                                                        .translationX(targetTransX)
                                                        .setDuration(150)
                                                        .start()
                                                }
                                            }
                                        }
                                    }
                                    return@setOnTouchListener true
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (isDragging) {
                                    isDragging = false
                                    clearBubbleRipple(v)
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                    holder.row.requestDisallowInterceptTouchEvent(false)

                                    if (isExtracted) {
                                        // Dropped outside group into the list!
                                        val pos = holder.bindingAdapterPosition
                                        if (pos != RecyclerView.NO_POSITION && i in group.apps.indices) {
                                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            val bubbleLoc = IntArray(2)
                                            v.getLocationOnScreen(bubbleLoc)
                                            val startX = bubbleLoc[0].toFloat()
                                            val startY = bubbleLoc[1].toFloat()
                                            val startW = v.width.toFloat()

                                            val otherAppInfo = if (group.apps.size == 2) {
                                                val otherIndex = 1 - i
                                                val otherChild = holder.row.getChildAt(otherIndex)
                                                if (otherChild != null && otherIndex in group.apps.indices) {
                                                    val rowLoc = IntArray(2)
                                                    holder.row.getLocationOnScreen(rowLoc)
                                                    val childLeft = rowLoc[0] + otherChild.left.toFloat()
                                                    val childTop = rowLoc[1] + otherChild.top.toFloat()
                                                    val childW = if (otherChild.width > 0) otherChild.width.toFloat() else ((holder.row.width - dp(8)) / 2f)
                                                    OtherAppMorphInfo(
                                                        app = group.apps[otherIndex],
                                                        startX = childLeft,
                                                        startY = childTop,
                                                        startW = childW
                                                    )
                                                } else null
                                            } else null

                                            v.visibility = View.INVISIBLE
                                            v.alpha = 1f
                                            v.translationX = 0f
                                            v.translationY = 0f
                                            v.scaleX = 1f
                                            v.scaleY = 1f
                                            v.translationZ = 0f
                                            holder.row.translationZ = 0f
                                            for (j in 0 until holder.row.childCount) {
                                                holder.row.getChildAt(j).translationX = 0f
                                            }
                                            onPullOutFromGroup(pos, i, event.rawX, event.rawY, startX, startY, startW, app, otherAppInfo)
                                        } else {
                                            v.alpha = 1f
                                            onBubbleDragCancel(v)
                                        }
                                        return@setOnTouchListener true
                                    } else {
                                        // Reordered inside group
                                        val slotStep = if (holder.row.childCount > 1) {
                                            val diff = (holder.row.getChildAt(1).left - holder.row.getChildAt(0).left).toFloat()
                                            if (diff > 0f) diff else (v.width + dp(8)).toFloat()
                                        } else 0f

                                        val finalTransX = (currentTargetSlot - i) * slotStep
                                        val targetFinalSlot = currentTargetSlot
                                        v.animate()
                                            .translationX(finalTransX)
                                            .translationY(0f)
                                            .scaleX(1.0f)
                                            .scaleY(1.0f)
                                            .setDuration(150)
                                            .withEndAction {
                                                v.translationX = 0f
                                                v.translationY = 0f
                                                v.translationZ = 0f
                                                holder.row.translationZ = 0f
                                                for (j in 0 until holder.row.childCount) {
                                                    holder.row.getChildAt(j).translationX = 0f
                                                }
                                                if (targetFinalSlot != i && targetFinalSlot in group.apps.indices && i in group.apps.indices) {
                                                    val movedApp = group.apps.removeAt(i)
                                                    group.apps.add(targetFinalSlot, movedApp)
                                                    onDragGroupFinished()
                                                    val pos = holder.bindingAdapterPosition
                                                    if (pos != RecyclerView.NO_POSITION) {
                                                        notifyItemChanged(pos)
                                                    }
                                                }
                                            }
                                            .start()
                                        return@setOnTouchListener true
                                    }
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                if (isDragging) {
                                    isDragging = false
                                    v.alpha = 1f
                                    onBubbleDragCancel(v)
                                    clearBubbleRipple(v)
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                    holder.row.requestDisallowInterceptTouchEvent(false)
                                    v.animate()
                                        .translationX(0f)
                                        .translationY(0f)
                                        .scaleX(1.0f)
                                        .scaleY(1.0f)
                                        .setDuration(150)
                                        .withEndAction {
                                            v.translationZ = 0f
                                            holder.row.translationZ = 0f
                                            for (j in 0 until holder.row.childCount) {
                                                holder.row.getChildAt(j).translationX = 0f
                                            }
                                        }
                                        .start()
                                    return@setOnTouchListener true
                                }
                            }
                        }
                        false
                    }
                    setOnClickListener { onItemClick(app) }
                }

                val iconView = if (bubble.childCount > 0) {
                    bubble.getChildAt(0) as ImageView
                } else {
                    ImageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        bubble.addView(this)
                    }
                }
                val icon = app.icon ?: loadIcon?.invoke(app) { loadedDrawable ->
                    iconView.setImageDrawable(loadedDrawable)
                }
                iconView.setImageDrawable(icon ?: defaultIcon)
                val isHiddenBubble = isGroupHidden || (position == hiddenBubbleGroupIndex && i == hiddenBubbleIndex)
                bubble.alpha = if (isHiddenBubble) 0f else 1.0f
                bubble.visibility = View.VISIBLE
                if (!isExisting) {
                    holder.row.addView(bubble)
                }
            }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
