package su.myt.home.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import su.myt.home.R

class ItemMorphView(context: Context) : FrameLayout(context) {

    val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val labelView = TextView(context).apply {
        setTextColor(context.getColor(R.color.launcher_foreground))
        textSize = 19f
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }

    private val surface = GradientDrawable().apply {
        setColor(context.getColor(R.color.launcher_row))
        cornerRadius = dp(20).toFloat()
    }

    init {
        background = surface
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        clipChildren = true
        addView(iconView, LayoutParams(dp(36), dp(36)))
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, dp(64)))
    }

    fun bind(icon: Drawable?, label: String) {
        iconView.setImageDrawable(icon)
        labelView.text = label
    }

    fun setFrame(x: Float, y: Float, width: Float, rowProgress: Float) {
        this.x = x
        this.y = y
        val w = width.toInt()
        val lp = layoutParams
        if (lp != null && lp.width != w) {
            lp.width = w
            layoutParams = lp
        }

        val iconSize = dp(36)
        val p = rowProgress.coerceIn(0f, 1f)
        val bubbleIconX = (width - iconSize) / 2f
        val rowIconX = dp(16).toFloat()
        val iconX = bubbleIconX + (rowIconX - bubbleIconX) * p
        iconView.x = iconX
        iconView.y = (dp(64) - iconSize) / 2f

        val labelLeft = iconX + iconSize + dp(12)
        labelView.x = labelLeft
        labelView.alpha = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)

        val maxLabelW = (width - labelLeft - dp(16)).coerceAtLeast(0f).toInt()
        val labelLp = labelView.layoutParams
        if (labelLp != null && labelLp.width != maxLabelW) {
            labelLp.width = maxLabelW
            labelView.layoutParams = labelLp
        }
    }

    fun updateProgress(
        progress: Float,
        startX: Float,
        startY: Float,
        startW: Float,
        endX: Float,
        endY: Float,
        endW: Float,
        isExpanding: Boolean
    ) {
        val p = progress.coerceIn(0f, 1f)
        setFrame(
            x = startX + (endX - startX) * p,
            y = startY + (endY - startY) * p,
            width = startW + (endW - startW) * p,
            rowProgress = if (isExpanding) p else 1f - p
        )
    }

    fun updateDragState(
        areaX: Float,
        areaY: Float,
        morphProgress: Float,
        targetBubbleW: Float,
        fullRowW: Float,
        listPaddingLeft: Float
    ) {
        val p = morphProgress.coerceIn(0f, 1f)
        val currentW = targetBubbleW + (fullRowW - targetBubbleW) * p
        val contractedX = areaX - targetBubbleW / 2f
        val expandedX = listPaddingLeft
        setFrame(
            x = contractedX + (expandedX - contractedX) * p,
            y = areaY - dp(32),
            width = currentW,
            rowProgress = p
        )
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
