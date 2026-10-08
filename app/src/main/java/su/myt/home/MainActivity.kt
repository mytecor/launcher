package su.myt.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.util.Collections
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class App(
        val label: String,
        val badgedLabel: String,
        val component: ComponentName,
        val user: UserHandle,
        val userSerial: Long,
        val icon: Drawable? = null
    ) {
        val id: String get() = if (userSerial != 0L) "$userSerial:${component.flattenToString()}" else component.flattenToString()
    }

    private val backgroundColor by lazy { getColor(R.color.launcher_background) }
    private val foregroundColor by lazy { getColor(R.color.launcher_foreground) }
    private val muted by lazy { getColor(R.color.launcher_muted) }
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val pinnedOrder: MutableList<String> by lazy {
        val saved = prefs.getString("pinned_order", null)
        if (saved != null) {
            try {
                val json = JSONArray(saved)
                val list = mutableListOf<String>()
                for (i in 0 until json.length()) {
                    list.add(json.getString(i))
                }
                list
            } catch (_: Exception) {
                mutableListOf()
            }
        } else {
            val legacy = prefs.getStringSet("pinned", emptySet()) ?: emptySet()
            legacy.toMutableList()
        }
    }
    private var apps = emptyList<App>()
    private var shown: MutableList<App> = mutableListOf()
    private var isSearching = false
    private var loading = true
    private lateinit var clearButton: ImageButton
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: AppAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reloadApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reloadApps()
        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) = reloadApps()
        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) = reloadApps()
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            clipChildren = false
            clipToPadding = false
        }
        // The regular insets dispatch contains the animation's END state.
        // While IME is moving, use its per-frame insets instead of jumping there.
        val imeAnimations = mutableSetOf<WindowInsetsAnimation>()
        fun applyInsets(insets: WindowInsets) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            root.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), maxOf(bars.bottom, ime.bottom) + dp(16))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (imeAnimations.isEmpty()) applyInsets(insets)
            insets
        }
        root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) imeAnimations.add(animation)
            }

            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (imeAnimations.isNotEmpty()) applyInsets(insets)
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimation) {
                if (imeAnimations.remove(animation) && imeAnimations.isEmpty()) {
                    root.rootWindowInsets?.let(::applyInsets)
                }
            }
        })
        val area = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        adapter = AppAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        list = RecyclerView(this).apply {
            this.layoutManager = layoutManager
            this.adapter = this@MainActivity.adapter
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            itemAnimator = DefaultItemAnimator()
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    val pos = parent.getChildAdapterPosition(view)
                    if (pos != RecyclerView.NO_POSITION && pos > 0) {
                        outRect.top = dp(8)
                    }
                }
            })
        }
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = !isSearching

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (isSearching) return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (from < to) {
                    for (i in from until to) {
                        Collections.swap(shown, i, i + 1)
                    }
                } else {
                    for (i in from downTo to + 1) {
                        Collections.swap(shown, i, i - 1)
                    }
                }
                pinnedOrder.clear()
                pinnedOrder.addAll(shown.map { it.id })
                adapter.notifyItemMoved(from, to)
                return true
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
                savePinned()
            }
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(list)
        area.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply {
            setTextColor(muted)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        area.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val inputRow = LinearLayout(this).apply {
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(getColor(R.color.launcher_input)); cornerRadius = dp(20).toFloat() }
        }
        search = EditText(this).apply {
            setHint(R.string.search_hint)
            setTextColor(foregroundColor)
            setHintTextColor(muted)
            textSize = 18f
            setSingleLine(true)
            background = null
            minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            setPadding(dp(16), dp(12), 0, dp(12))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    shown.lastOrNull()?.let(::launch)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        clearButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(muted)
            scaleType = ImageView.ScaleType.CENTER
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.launcher_row))
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                ColorStateList.valueOf((getColor(R.color.launcher_accent) and 0x00FFFFFF) or 0x30000000),
                circle, mask
            )
            visibility = View.INVISIBLE
            contentDescription = getString(R.string.clear_search)
            setOnClickListener { search.text.clear(); showKeyboard() }
        }
        inputRow.addView(clearButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        root.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        setContentView(root)
        search.setText(savedInstanceState?.getString("query") ?: "")
        render()

        try {
            getSystemService(LauncherApps::class.java)?.registerCallback(launcherAppsCallback)
        } catch (_: Exception) {}
        val profileFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
        }
        registerReceiver(profileReceiver, profileFilter)
    }

    override fun onResume() {
        super.onResume()
        reloadApps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::search.isInitialized) showKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        search.text.clear()
        showKeyboard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", search.text.toString())
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        search.text.clear()
        showKeyboard()
    }

    override fun onDestroy() {
        worker.shutdown()
        try {
            unregisterReceiver(profileReceiver)
        } catch (_: Exception) {}
        try {
            getSystemService(LauncherApps::class.java)?.unregisterCallback(launcherAppsCallback)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private val requestKeyboard = Runnable {
        if (hasWindowFocus() && search.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true) {
            window.insetsController?.show(WindowInsets.Type.ime())
        }
    }

    override fun onPause() {
        search.removeCallbacks(requestKeyboard)
        super.onPause()
    }

    private fun showKeyboard() {
        search.requestFocus()
        search.removeCallbacks(requestKeyboard)
        if (hasWindowFocus()) search.post(requestKeyboard)
    }

    private fun savePinned() {
        val json = JSONArray(pinnedOrder)
        prefs.edit()
            .putString("pinned_order", json.toString())
            .putStringSet("pinned", pinnedOrder.toSet())
            .apply()
    }

    private fun isPinned(app: App): Boolean {
        return app.id in pinnedOrder || (app.userSerial == 0L && app.component.flattenToString() in pinnedOrder)
    }

    private fun reloadApps() {
        worker.execute {
            val launcherApps = getSystemService(LauncherApps::class.java)
            val userManager = getSystemService(UserManager::class.java)
            val profiles = try {
                userManager?.userProfiles ?: listOf(Process.myUserHandle())
            } catch (_: Exception) {
                listOf(Process.myUserHandle())
            }
            val loaded = mutableListOf<App>()
            var successLauncherApps = false

            if (launcherApps != null) {
                try {
                    for (user in profiles) {
                        val userSerial = try {
                            userManager?.getSerialNumberForUser(user) ?: 0L
                        } catch (_: Exception) {
                            0L
                        }
                        val activities = launcherApps.getActivityList(null, user)
                        for (info in activities) {
                            if (info.componentName.packageName == packageName) continue
                            val label = info.label.toString()
                            val badgedLabel = try {
                                packageManager.getUserBadgedLabel(label, user).toString()
                            } catch (_: Exception) {
                                label
                            }
                            val icon = try {
                                info.getBadgedIcon(0)
                            } catch (_: Exception) {
                                null
                            }
                            loaded.add(
                                App(
                                    label = label,
                                    badgedLabel = badgedLabel,
                                    component = info.componentName,
                                    user = user,
                                    userSerial = userSerial,
                                    icon = icon
                                )
                            )
                        }
                    }
                    successLauncherApps = true
                } catch (_: Exception) {
                    // Fallback to PackageManager if LauncherApps is unavailable or throws
                }
            }

            val finalApps = if (successLauncherApps && loaded.isNotEmpty()) {
                loaded
            } else {
                val myUser = Process.myUserHandle()
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map {
                        val label = it.loadLabel(packageManager).toString()
                        val icon = try { it.loadIcon(packageManager) } catch (_: Exception) { null }
                        val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                        App(
                            label = label,
                            badgedLabel = label,
                            component = component,
                            user = myUser,
                            userSerial = 0L,
                            icon = icon
                        )
                    }
            }.distinctBy { it.id }.sortedWith(
                compareBy<App> { it.label.lowercase() }
                    .thenBy { it.userSerial }
                    .thenBy { it.id }
            )

            runOnUiThread {
                if (!isDestroyed) {
                    if (!prefs.contains("pinned_order") && pinnedOrder.isNotEmpty()) {
                        val existingIds = pinnedOrder.toSet()
                        val sorted = finalApps.filter { it.id in existingIds || it.component.flattenToString() in existingIds }.map { it.id }
                        pinnedOrder.clear()
                        pinnedOrder.addAll(sorted)
                        savePinned()
                    }
                    apps = finalApps
                    loading = false
                    render()
                }
            }
        }
    }

    private fun matchScore(app: App, query: String): Int? {
        val score1 = FuzzySearch.score(app.label, query)
        if (app.badgedLabel == app.label) return score1
        val score2 = FuzzySearch.score(app.badgedLabel, query)
        return when {
            score1 == null -> score2
            score2 == null -> score1
            else -> maxOf(score1, score2)
        }
    }

    private fun render() {
        if (!::search.isInitialized) return
        if (::clearButton.isInitialized) {
            clearButton.visibility = if (search.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }
        val query = search.text.toString().trim().take(100)
        isSearching = query.isNotEmpty()
        shown = if (!isSearching) {
            val appMap = apps.associateBy { it.id }
            val homeApps = mutableListOf<App>()
            for (id in pinnedOrder) {
                val app = appMap[id] ?: apps.find { it.userSerial == 0L && it.component.flattenToString() == id }
                if (app != null) homeApps.add(app)
            }
            homeApps
        } else {
            apps.mapNotNull { app -> matchScore(app, query)?.let { app to it } }
                .sortedWith(
                    compareByDescending<Pair<App, Int>> { it.second }
                        .thenBy { it.first.label.lowercase() }
                        .thenBy { it.first.userSerial }
                        .thenBy { it.first.id }
                )
                .map { it.first }.reversed().toMutableList()
        }
        adapter.notifyDataSetChanged()
        empty.text = when {
            loading -> getString(R.string.loading_apps)
            query.isNotEmpty() -> getString(R.string.no_results)
            else -> ""
        }
        empty.visibility = if (shown.isEmpty() && empty.text.isNotEmpty()) View.VISIBLE else View.GONE
        if (shown.isNotEmpty()) list.scrollToPosition(shown.lastIndex)
    }

    private fun launch(app: App) {
        try {
            val launcherApps = getSystemService(LauncherApps::class.java)
            launcherApps.startMainActivity(app.component, app.user, null, null)
            search.text.clear()
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.app_unavailable), Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(app.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
                search.text.clear()
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.launch_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        inner class ViewHolder(
            val row: LinearLayout,
            val iconView: ImageView,
            val labelView: TextView,
            val starView: ImageView
        ) : RecyclerView.ViewHolder(row) {
            init {
                row.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos in shown.indices) {
                        launch(shown[pos])
                    }
                }
                row.setOnLongClickListener {
                    if (isSearching) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION && pos in shown.indices) {
                            val app = shown[pos]
                            val added = if (isPinned(app)) {
                                pinnedOrder.removeAll { it == app.id || (app.userSerial == 0L && it == app.component.flattenToString()) }
                                false
                            } else {
                                pinnedOrder.add(app.id)
                                true
                            }
                            savePinned()
                            render()
                            Toast.makeText(
                                this@MainActivity,
                                getString(if (added) R.string.app_pinned else R.string.app_unpinned, app.badgedLabel),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    } else {
                        false
                    }
                }
            }
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val iconView = ImageView(this@MainActivity)
            val labelView = TextView(this@MainActivity).apply {
                setTextColor(foregroundColor)
                textSize = 19f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val starView = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_star)
                imageTintList = ColorStateList.valueOf(getColor(R.color.launcher_accent))
                scaleType = ImageView.ScaleType.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val row = LinearLayout(this@MainActivity).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                isBaselineAligned = false
                val surface = GradientDrawable().apply {
                    setColor(getColor(R.color.launcher_row))
                    cornerRadius = dp(20).toFloat()
                }
                val mask = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(20).toFloat()
                }
                background = RippleDrawable(
                    ColorStateList.valueOf((getColor(R.color.launcher_accent) and 0x00FFFFFF) or 0x24000000),
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
            val app = shown[position]
            holder.iconView.setImageDrawable(
                app.icon ?: try {
                    packageManager.getActivityIcon(app.component)
                } catch (_: Exception) {
                    packageManager.defaultActivityIcon
                }
            )
            holder.labelView.text = app.label
            holder.starView.visibility = if (isSearching && isPinned(app)) View.VISIBLE else View.GONE
            holder.row.contentDescription = if (isPinned(app)) getString(R.string.pinned_description, app.badgedLabel) else app.badgedLabel
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
