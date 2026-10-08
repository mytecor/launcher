package su.myt.home

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import su.myt.home.data.AppLauncher
import su.myt.home.data.AppRepository
import su.myt.home.data.LaunchResult
import su.myt.home.data.PinnedAppsStorage
import su.myt.home.model.App
import su.myt.home.pinned.PinnedAppsManager
import su.myt.home.pinned.PinnedItemTouchHelperCallback
import su.myt.home.search.SearchEngine
import su.myt.home.ui.AppAdapter

class MainActivity : Activity() {

    private val foregroundColor by lazy { getColor(R.color.launcher_foreground) }
    private val muted by lazy { getColor(R.color.launcher_muted) }

    private val appRepository by lazy { AppRepository(this) }
    private val appLauncher by lazy { AppLauncher(this) }
    private val pinnedManager by lazy { PinnedAppsManager(PinnedAppsStorage(this)) }
    private val searchEngine = SearchEngine()

    private var allApps = emptyList<App>()
    private var isSearching = false
    private var loading = true

    private lateinit var clearButton: ImageButton
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: AppAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var area: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this)

        val imeAnimations = mutableSetOf<WindowInsetsAnimation>()
        var lastImeHeight = 0
        var inputRowHeight = dp(56)
        var lastBarsTop = 0

        fun updateListPadding() {
            val halfInput = inputRowHeight / 2
            val overlapBottomPadding = (inputRowHeight - halfInput) + dp(16)
            if (::area.isInitialized) {
                (area.layoutParams as? FrameLayout.LayoutParams)?.let {
                    if (it.bottomMargin != halfInput) {
                        it.bottomMargin = halfInput
                        area.layoutParams = it
                    }
                }
            }
            if (::list.isInitialized) {
                list.setPadding(0, lastBarsTop + dp(16), 0, overlapBottomPadding)
            }
            if (::empty.isInitialized) {
                (empty.layoutParams as? FrameLayout.LayoutParams)?.let {
                    if (it.bottomMargin != overlapBottomPadding) {
                        it.bottomMargin = overlapBottomPadding
                        empty.layoutParams = it
                    }
                }
            }
        }

        fun applyInsets(insets: WindowInsets) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            if (ime.bottom > 0) lastImeHeight = ime.bottom
            val isImeVisible = insets.isVisible(WindowInsets.Type.ime())
            val effectiveImeBottom = when {
                ime.bottom > 0 -> ime.bottom
                isImeVisible -> lastImeHeight
                else -> 0
            }
            lastBarsTop = bars.top
            root.setPadding(bars.left + dp(16), 0, bars.right + dp(16), maxOf(bars.bottom, effectiveImeBottom) + dp(16))
            updateListPadding()
        }

        root.setOnApplyWindowInsetsListener { _, insets ->
            if (imeAnimations.isEmpty()) applyInsets(insets)
            insets
        }
        root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                    imeAnimations.add(animation)
                }
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

        area = FrameLayout(this)
        root.addView(area, FrameLayout.LayoutParams(-1, -1).apply {
            bottomMargin = inputRowHeight / 2
        })

        adapter = AppAdapter(
            context = this,
            isItemPinned = { app -> pinnedManager.isPinned(app) },
            shouldShowStar = { isSearching },
            onItemClick = { app -> launch(app) },
            onItemLongClick = { app ->
                if (isSearching) {
                    val added = pinnedManager.togglePin(app)
                    render()
                    Toast.makeText(
                        this,
                        getString(if (added) R.string.app_pinned else R.string.app_unpinned, app.badgedLabel),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                } else {
                    false
                }
            }
        )

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        list = RecyclerView(this).apply {
            this.layoutManager = layoutManager
            this.adapter = this@MainActivity.adapter
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(16), 0, dp(16))
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
        root.rootWindowInsets?.let(::applyInsets)

        val touchCallback = PinnedItemTouchHelperCallback(
            isDragEnabled = { !isSearching },
            onMoveItem = { from, to ->
                val moved = pinnedManager.moveItem(adapter.items, from, to)
                if (moved) adapter.notifyItemMoved(from, to)
                moved
            },
            onDragFinished = {
                pinnedManager.saveOrder()
            }
        )
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(list)
        area.addView(list, FrameLayout.LayoutParams(-1, -1))

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!isSearching && list.findChildViewUnder(e.x, e.y) == null) {
                    openWallpaperPicker()
                }
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }
        })

        area.setOnLongClickListener {
            if (!isSearching) {
                openWallpaperPicker()
                true
            } else false
        }

        empty = TextView(this).apply {
            setTextColor(muted)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnLongClickListener {
                if (!isSearching) {
                    openWallpaperPicker()
                    true
                } else false
            }
        }
        area.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = (inputRowHeight - inputRowHeight / 2) + dp(16) })

        root.setOnLongClickListener {
            if (!isSearching) {
                openWallpaperPicker()
                true
            } else false
        }

        val inputRow = LinearLayout(this).apply {
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            translationZ = dp(2).toFloat()
            background = GradientDrawable().apply {
                setColor(getColor(R.color.launcher_input))
                cornerRadius = dp(20).toFloat()
            }
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val h = bottom - top
                if (h > 0 && h != inputRowHeight) {
                    inputRowHeight = h
                    updateListPadding()
                }
            }
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
                    adapter.items.lastOrNull()?.let(::launch)
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
            imageTintList = ColorStateList.valueOf(muted)
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
        root.addView(inputRow, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        search.setText(savedInstanceState?.getString("query") ?: "")
        render()

        appRepository.startObserving { reloadApps() }
    }

    override fun onResume() {
        super.onResume()
        if (::search.isInitialized) showKeyboard()
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        search.text.clear()
        showKeyboard()
    }

    override fun onDestroy() {
        appRepository.shutdown()
        super.onDestroy()
    }

    private val requestKeyboard = Runnable {
        if (hasWindowFocus() && search.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true) {
            window.insetsController?.show(WindowInsets.Type.ime())
            getSystemService(InputMethodManager::class.java)?.showSoftInput(search, 0)
        }
    }

    override fun onPause() {
        search.removeCallbacks(requestKeyboard)
        super.onPause()
    }

    private fun showKeyboard() {
        search.requestFocus()
        search.removeCallbacks(requestKeyboard)
        window.insetsController?.show(WindowInsets.Type.ime())
        getSystemService(InputMethodManager::class.java)?.showSoftInput(search, 0)
    }

    private fun reloadApps() {
        appRepository.loadApps { loaded ->
            runOnUiThread {
                if (!isDestroyed) {
                    if (allApps != loaded || loading) {
                        pinnedManager.syncWithLoadedApps(loaded)
                        allApps = loaded
                        loading = false
                        render()
                    }
                }
            }
        }
    }

    private fun render() {
        if (!::search.isInitialized) return
        if (::clearButton.isInitialized) {
            clearButton.visibility = if (search.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }
        val query = search.text.toString().trim().take(100)
        isSearching = query.isNotEmpty()

        val items = if (!isSearching) {
            // Режим закрепов (Home)
            pinnedManager.getPinnedApps(allApps)
        } else {
            // Режим поиска (Search)
            searchEngine.search(allApps, query).toMutableList()
        }

        adapter.items = items
        adapter.notifyDataSetChanged()

        empty.text = when {
            loading -> getString(R.string.loading_apps)
            query.isNotEmpty() -> getString(R.string.no_results)
            else -> ""
        }
        empty.visibility = if (items.isEmpty() && empty.text.isNotEmpty()) View.VISIBLE else View.GONE
        if (items.isNotEmpty()) list.scrollToPosition(items.lastIndex)
    }

    private fun launch(app: App) {
        when (appLauncher.launch(app)) {
            is LaunchResult.Success -> {
                search.text.clear()
            }
            is LaunchResult.NotFound -> {
                Toast.makeText(this, getString(R.string.app_unavailable), Toast.LENGTH_SHORT).show()
            }
            is LaunchResult.Failed -> {
                Toast.makeText(this, getString(R.string.launch_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openWallpaperPicker() {
        val intent = Intent(Intent.ACTION_SET_WALLPAPER)
        val chooser = Intent.createChooser(intent, getString(R.string.set_wallpaper))
        try {
            startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.wallpaper_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
