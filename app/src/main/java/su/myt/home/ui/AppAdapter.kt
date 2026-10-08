package su.myt.home.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import su.myt.home.R
import su.myt.home.model.App

class AppAdapter(
    private val context: Context,
    private val isItemPinned: (App) -> Boolean,
    private val shouldShowStar: () -> Boolean,
    private val onItemClick: (App) -> Unit,
    private val onItemLongClick: (App) -> Boolean
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    var items: MutableList<App> = mutableListOf()

    inner class ViewHolder(
        val row: LinearLayout,
        val iconView: ImageView,
        val labelView: TextView,
        val starView: ImageView
    ) : RecyclerView.ViewHolder(row) {
        init {
            row.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                    onItemClick(items[pos])
                }
            }
            row.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                    onItemLongClick(items[pos])
                } else {
                    false
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
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
        val row = LinearLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            isBaselineAligned = false
            val surface = GradientDrawable().apply {
                setColor(context.getColor(R.color.launcher_row))
                cornerRadius = dp(20).toFloat()
            }
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            background = RippleDrawable(
                ColorStateList.valueOf((context.getColor(R.color.launcher_accent) and 0x00FFFFFF) or 0x24000000),
                surface, mask
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(labelView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(starView, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        return ViewHolder(row, iconView, labelView, starView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.iconView.setImageDrawable(
            app.icon ?: try {
                context.packageManager.getActivityIcon(app.component)
            } catch (_: Exception) {
                context.packageManager.defaultActivityIcon
            }
        )
        holder.labelView.text = app.label
        val pinned = isItemPinned(app)
        holder.starView.visibility = if (shouldShowStar() && pinned) View.VISIBLE else View.GONE
        holder.row.contentDescription = if (pinned) {
            context.getString(R.string.pinned_description, app.badgedLabel)
        } else {
            app.badgedLabel
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
