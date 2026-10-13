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
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import su.myt.home.data.AppLauncher
import su.myt.home.data.AppRepository
import su.myt.home.data.LaunchResult
import su.myt.home.data.PinnedAppsStorage
import su.myt.home.model.App
import su.myt.home.model.AppGroup
import su.myt.home.pinned.PinnedAppsManager
import su.myt.home.search.SearchEngine
import su.myt.home.ui.AppAdapter
import su.myt.home.ui.ItemDragSession
import su.myt.home.ui.ItemMorphView
import su.myt.home.ui.OtherAppMorphInfo

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
    private lateinit var adapter: AppAdapter
    private lateinit var itemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper
    private var pinnedTouchCallback: su.myt.home.pinned.PinnedItemTouchHelperCallback? = null
    private lateinit var area: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var drag: ItemDragSession
    private var listInteraction = false
    private var pendingReload = false
    private var lastFingerRawX = 0f
    private var lastFingerRawY = 0f

    private fun beginListInteraction() {
        listInteraction = true
    }

    private fun endListInteraction() {
        listInteraction = false
        if (pendingReload) {
            pendingReload = false
            reloadApps()
        }
    }

    private fun cancelActiveMorph(restore: View? = null) {
        if (::drag.isInitialized) drag.cancel(restore)
        if (::adapter.isInitialized) {
            adapter.hiddenRowPosition = -1
            adapter.hiddenRowPosition2 = -1
            adapter.hiddenBubbleGroupIndex = -1
            adapter.hiddenBubbleIndex = -1
        }
        if (::list.isInitialized) {
            if (::adapter.isInitialized) adapter.resetDragChrome(list)
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                child.alpha = 1f
                child.translationX = 0f
                child.translationY = 0f
                if (child is LinearLayout) {
                    for (j in 0 until child.childCount) {
                        val nested = child.getChildAt(j)
                        nested.alpha = 1f
                        nested.translationX = 0f
                        nested.translationY = 0f
                    }
                }
            }
        }
        endListInteraction()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        val imeAnimations = mutableSetOf<WindowInsetsAnimation>()
        var lastImeHeight = 0
        var inputRowHeight = dp(56)
        var lastBarsTop = 0

        fun updateListPadding() {
            val bottomPad = inputRowHeight + dp(24)
            if (::list.isInitialized) {
                list.setPadding(0, lastBarsTop + dp(16), 0, bottomPad)
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

        area = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(area, FrameLayout.LayoutParams(-1, -1))

        adapter = AppAdapter(
            context = this,
            isItemPinned = { app -> pinnedManager.isPinned(app) },
            shouldShowStar = { isSearching },
            loadIcon = { app, onLoaded -> appRepository.loadIcon(app, onLoaded) },
            onItemClick = { app -> launch(app) },
            onItemLongClick = { app, _, _, _, _ ->
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
            },
            onPullOutFromGroup = { groupIndex, appIndex, dropRawX, dropRawY, startX, startY, startW, app, otherAppInfo ->
                finishListDrag(groupIndex, appIndex, dropRawX, dropRawY, startX, startY, startW, app, otherAppInfo)
            },
            onBubbleDragMove = { rawX, rawY, app, sourceGroupPos, _, bubbleView ->
                beginListInteraction()
                drag.ensureOverlay(app, bubbleView, asRow = false)
                drag.moveFinger(rawX, rawY)
                val listLoc = IntArray(2)
                list.getLocationOnScreen(listLoc)
                val listY = rawY - listLoc[1]
                val target = drag.groupingTarget(listY, sourceGroupPos) { to ->
                    to != sourceGroupPos &&
                        (adapter.items.getOrNull(to)?.apps?.size ?: PinnedAppsManager.MAX_GROUP_SIZE) < PinnedAppsManager.MAX_GROUP_SIZE
                }
                drag.setGrouping(target != null, target)
            },
            onBubbleDragCancel = { bubbleView ->
                cancelActiveMorph(bubbleView)
            },
            onDragGroupFinished = {
                pinnedManager.syncOrderFromGroups(adapter.items)
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
            clipChildren = false
            setPadding(0, dp(16), 0, dp(80))
            itemAnimator = object : DefaultItemAnimator() {
                init {
                    supportsChangeAnimations = false
                }

                private fun isRowHidden(holder: RecyclerView.ViewHolder): Boolean {
                    val pos = holder.bindingAdapterPosition
                    return pos == this@MainActivity.adapter.hiddenRowPosition ||
                           pos == this@MainActivity.adapter.hiddenRowPosition2 ||
                           (pos == this@MainActivity.adapter.hiddenBubbleGroupIndex && this@MainActivity.adapter.hiddenBubbleIndex == -2)
                }

                override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
                    val hidden = isRowHidden(holder)
                    holder.itemView.alpha = if (hidden) 0f else 1f
                    dispatchAddFinished(holder)
                    return false
                }

                override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
                    holder.itemView.alpha = 0f
                    dispatchRemoveFinished(holder)
                    return false
                }

                override fun animateChange(
                    oldHolder: RecyclerView.ViewHolder,
                    newHolder: RecyclerView.ViewHolder?,
                    fromX: Int, fromY: Int, toX: Int, toY: Int
                ): Boolean {
                    oldHolder.itemView.alpha = 0f
                    dispatchChangeFinished(oldHolder, true)
                    if (newHolder != null) {
                        val hidden = isRowHidden(newHolder)
                        newHolder.itemView.alpha = if (hidden) 0f else 1f
                        dispatchChangeFinished(newHolder, false)
                    }
                    return false
                }

                override fun animateMove(
                    holder: RecyclerView.ViewHolder,
                    fromX: Int,
                    fromY: Int,
                    toX: Int,
                    toY: Int
                ): Boolean {
                    dispatchMoveFinished(holder)
                    return false
                }
            }
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

        drag = ItemDragSession(
            context = this,
            area = area,
            list = list,
            dp = { dp(it) },
            resolveIcon = { app, view ->
                app.icon ?: appRepository.loadIcon(app) { loaded ->
                    view.iconView.setImageDrawable(loaded)
                } ?: packageManager.defaultActivityIcon
            }
        )

        val touchCallback = su.myt.home.pinned.PinnedItemTouchHelperCallback(
            isDragEnabled = { !isSearching },
            findGroupTarget = { centerY, fromPos ->
                drag.groupingTarget(centerY, fromPos) { to ->
                    pinnedManager.canGroup(adapter.items, fromPos, to)
                }
            },
            onGroupItems = { from, to -> finishRowGroup(from, to) },
            onMoveItem = { from, to ->
                val moved = pinnedManager.moveItem(adapter.items, from, to)
                if (moved) adapter.notifyItemMoved(from, to)
                moved
            },
            onDragStarted = { holder ->
                beginListInteraction()
                val pos = holder.bindingAdapterPosition
                val item = adapter.items.getOrNull(pos) ?: return@PinnedItemTouchHelperCallback
                if (!item.isGroup) {
                    item.apps.firstOrNull()?.let { drag.ensureOverlayFromRow(it, holder.itemView) }
                }
            },
            onDragHover = { holder, target ->
                val pos = holder.bindingAdapterPosition
                val item = adapter.items.getOrNull(pos)
                drag.setGrouping(target != null, target)
                if (item != null && !item.isGroup) {
                    if (target != null) {
                        drag.moveFinger(lastFingerRawX, lastFingerRawY)
                    } else {
                        drag.moveToViewCenter(holder.itemView)
                    }
                }
            },
            onDragFinished = { holder ->
                cancelActiveMorph(holder?.itemView)
                pinnedManager.saveOrder()
            }
        )
        pinnedTouchCallback = touchCallback
        itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(touchCallback)
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
            setHint(if (loading) R.string.loading_apps else R.string.search_hint)
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
                    adapter.items.lastOrNull()?.apps?.firstOrNull()?.let(::launch)
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

        val cached = appRepository.loadCachedApps()
        if (cached.isNotEmpty()) {
            allApps = cached
            loading = false
            pinnedManager.syncWithLoadedApps(cached)
        }

        search.setText(savedInstanceState?.getString("query") ?: "")
        render()

        appRepository.startObserving { reloadApps() }
    }



    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastFingerRawX = ev.rawX
        lastFingerRawY = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> pinnedTouchCallback?.onFingerDown()
            MotionEvent.ACTION_UP -> pinnedTouchCallback?.onFingerUp()
            MotionEvent.ACTION_CANCEL -> pinnedTouchCallback?.onFingerCancel()
        }
        return super.dispatchTouchEvent(ev)
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
                    if (listInteraction) {
                        pendingReload = true
                        return@runOnUiThread
                    }
                    val appsChanged = allApps != loaded
                    pinnedManager.syncWithLoadedApps(loaded)
                    allApps = loaded
                    val wasLoading = loading
                    loading = false

                    if (appsChanged || wasLoading) {
                        render()
                    } else {
                        val query = search.text.toString().trim().take(100)
                        adapter.items = if (query.isEmpty()) {
                            pinnedManager.getPinnedGroups(allApps)
                        } else {
                            searchEngine.search(allApps, query).map { AppGroup(mutableListOf(it)) }.toMutableList()
                        }
                        adapter.notifyDataSetChanged()
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
            pinnedManager.getPinnedGroups(allApps)
        } else {
            // Режим поиска (Search)
            searchEngine.search(allApps, query).map { AppGroup(mutableListOf(it)) }.toMutableList()
        }

        adapter.items = items
        adapter.notifyDataSetChanged()

        search.setHint(if (loading) R.string.loading_apps else R.string.search_hint)

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


    private fun finishRowGroup(from: Int, to: Int) {
        val fromItem = adapter.items.getOrNull(from) ?: return
        val toItem = adapter.items.getOrNull(to) ?: return
        val appToGroup = fromItem.apps.firstOrNull() ?: return
        val overlay = drag.takeOverlay()
        val isTargetSingle = !toItem.isGroup
        val targetApp = if (isTargetSingle) toItem.apps.firstOrNull() else null
        val draggedView = list.findViewHolderForAdapterPosition(from)?.itemView
        val targetView = list.findViewHolderForAdapterPosition(to)?.itemView
        val draggedLoc = IntArray(2)
        draggedView?.getLocationOnScreen(draggedLoc)
        val fromAreaX = overlay?.x ?: drag.screenToAreaX(draggedLoc[0].toFloat())
        val fromAreaY = overlay?.y ?: drag.screenToAreaY(draggedLoc[1].toFloat())
        val fromW = overlay?.layoutParams?.width?.toFloat() ?: draggedView?.width?.toFloat() ?: 0f
        if (!pinnedManager.groupItems(adapter.items, from, to) || targetView == null) {
            cancelActiveMorph()
            return
        }
        val newTo = if (from < to) to - 1 else to
        adapter.notifyItemRemoved(from)
        adapter.notifyItemChanged(newTo)
        settleIntoGroup(
            draggedApp = appToGroup,
            overlay = overlay,
            fromAreaX = fromAreaX,
            fromAreaY = fromAreaY,
            fromW = fromW,
            targetPos = newTo,
            targetView = targetView,
            targetWasSingle = isTargetSingle,
            targetApp = targetApp,
            remaining = null,
            remainingPos = RecyclerView.NO_POSITION
        )
    }

    private fun finishListDrag(
        sourceGroupIndex: Int,
        sourceAppIndex: Int,
        dropRawX: Float,
        dropRawY: Float,
        startX: Float,
        startY: Float,
        startW: Float,
        app: App,
        remaining: OtherAppMorphInfo?
    ) {
        val hoverTarget = drag.hover
        val targetGroupPos = hoverTarget?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
        val overlay = drag.takeOverlay()
        val fromAreaX = overlay?.x ?: drag.screenToAreaX(startX)
        val fromAreaY = overlay?.y ?: drag.screenToAreaY(startY)
        val fromW = overlay?.layoutParams?.width?.toFloat() ?: startW
        drag.clearHover()

        if (hoverTarget != null && targetGroupPos in adapter.items.indices && targetGroupPos != sourceGroupIndex) {
            val targetGroup = adapter.items[targetGroupPos]
            if (targetGroup.apps.size < PinnedAppsManager.MAX_GROUP_SIZE) {
                val isTargetSingle = !targetGroup.isGroup
                val targetApp = if (isTargetSingle) targetGroup.apps.firstOrNull() else null
                val targetView = hoverTarget.itemView
                val extracted = pinnedManager.extractAppToGroup(adapter.items, sourceGroupIndex, sourceAppIndex, targetGroupPos)
                if (extracted) {
                    val remainingPos = if (remaining != null) sourceGroupIndex else RecyclerView.NO_POSITION
                    adapter.notifyItemChanged(targetGroupPos)
                    adapter.notifyItemChanged(sourceGroupIndex)
                    settleIntoGroup(
                        draggedApp = app,
                        overlay = overlay,
                        fromAreaX = fromAreaX,
                        fromAreaY = fromAreaY,
                        fromW = fromW,
                        targetPos = targetGroupPos,
                        targetView = targetView,
                        targetWasSingle = isTargetSingle,
                        targetApp = targetApp,
                        remaining = remaining,
                        remainingPos = remainingPos
                    )
                    return
                }
            }
        }

        val listLoc = IntArray(2)
        list.getLocationOnScreen(listLoc)
        val targetPos = drag.insertPos(dropRawX - listLoc[0], dropRawY - listLoc[1])
        val extracted = pinnedManager.extractAppToPosition(adapter.items, sourceGroupIndex, sourceAppIndex, targetPos)
        if (!extracted) {
            overlay?.let { area.removeView(it) }
            cancelActiveMorph()
            return
        }
        val newGroupPos = if (targetPos <= sourceGroupIndex) sourceGroupIndex + 1 else sourceGroupIndex
        adapter.hiddenRowPosition = targetPos
        adapter.hiddenRowPosition2 = if (remaining != null) newGroupPos else -1
        adapter.notifyItemInserted(targetPos)
        adapter.notifyItemChanged(newGroupPos)
        settleAsRow(
            draggedApp = app,
            overlay = overlay,
            fromAreaX = fromAreaX,
            fromAreaY = fromAreaY,
            fromW = fromW,
            targetPos = targetPos,
            remaining = remaining,
            remainingPos = newGroupPos
        )
    }

    private fun settleIntoGroup(
        draggedApp: App,
        overlay: ItemMorphView?,
        fromAreaX: Float,
        fromAreaY: Float,
        fromW: Float,
        targetPos: Int,
        targetView: View,
        targetWasSingle: Boolean,
        targetApp: App?,
        remaining: OtherAppMorphInfo?,
        remainingPos: Int
    ) {
        val areaOrigin = drag.areaLoc()
        val targetLoc = IntArray(2)
        targetView.getLocationOnScreen(targetLoc)
        val targetAreaX = (targetLoc[0] - areaOrigin[0]).toFloat()
        val targetAreaY = (targetLoc[1] - areaOrigin[1]).toFloat()
        val targetRowW = targetView.width.toFloat()
        val targetGroup = adapter.items[targetPos]
        val newGroupSize = targetGroup.apps.size
        val newBubbleSlot = newGroupSize - 1
        val gap = dp(8).toFloat()
        val destBubbleW = (targetRowW - (newGroupSize - 1) * gap) / newGroupSize
        val destDragX = targetAreaX + newBubbleSlot * (destBubbleW + gap)

        if (targetWasSingle) {
            adapter.hiddenBubbleGroupIndex = targetPos
            adapter.hiddenBubbleIndex = -2
        } else {
            adapter.hiddenBubbleGroupIndex = targetPos
            adapter.hiddenBubbleIndex = newBubbleSlot
        }
        if (remainingPos != RecyclerView.NO_POSITION) {
            adapter.hiddenRowPosition2 = remainingPos
        }

        val morph1 = overlay ?: drag.morphView(draggedApp, fromW.toInt())
        morph1.translationZ = dp(16).toFloat()
        val morphs = mutableListOf(
            ItemDragSession.Morph(morph1, fromAreaX, fromAreaY, fromW, destDragX, targetAreaY, destBubbleW, false)
        )
        if (targetWasSingle && targetApp != null) {
            val morph2 = drag.morphView(targetApp, targetRowW.toInt())
            morphs.add(ItemDragSession.Morph(morph2, targetAreaX, targetAreaY, targetRowW, targetAreaX, targetAreaY, destBubbleW, false))
        }
        if (remaining != null) {
            val destW = (list.width - list.paddingLeft - list.paddingRight).toFloat()
            val destX = list.paddingLeft.toFloat()
            val morph3 = drag.morphView(remaining.app, remaining.startW.toInt())
            morphs.add(
                ItemDragSession.Morph(
                    morph3,
                    remaining.startX - areaOrigin[0],
                    remaining.startY - areaOrigin[1],
                    remaining.startW,
                    destX,
                    remaining.startY - areaOrigin[1],
                    destW,
                    true
                )
            )
        }
        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this)
                val holder = list.findViewHolderForAdapterPosition(targetPos)
                if (holder != null) {
                    val loc = IntArray(2)
                    holder.itemView.getLocationOnScreen(loc)
                    val y = (loc[1] - areaOrigin[1]).toFloat()
                    morphs.forEach { if (!it.expanding) it.endY = y }
                }
                if (remainingPos != RecyclerView.NO_POSITION) {
                    val remainingHolder = list.findViewHolderForAdapterPosition(remainingPos)
                    if (remainingHolder != null) {
                        val loc = IntArray(2)
                        remainingHolder.itemView.getLocationOnScreen(loc)
                        val y = (loc[1] - areaOrigin[1]).toFloat()
                        morphs.filter { it.expanding }.forEach { it.endY = y }
                    }
                }
                return true
            }
        })
        drag.play(morphs) {
            adapter.hiddenBubbleGroupIndex = -1
            adapter.hiddenBubbleIndex = -1
            adapter.hiddenRowPosition2 = -1
            list.findViewHolderForAdapterPosition(targetPos)?.itemView?.alpha = 1f
            if (remainingPos != RecyclerView.NO_POSITION) {
                list.findViewHolderForAdapterPosition(remainingPos)?.itemView?.alpha = 1f
            }
            val groupHolder = list.findViewHolderForAdapterPosition(targetPos) as? AppAdapter.GroupViewHolder
            if (groupHolder != null) {
                groupHolder.row.alpha = 1f
                for (c in 0 until groupHolder.row.childCount) {
                    groupHolder.row.getChildAt(c)?.alpha = 1f
                }
            }
            pinnedManager.saveOrder()
            endListInteraction()
        }
    }

    private fun settleAsRow(
        draggedApp: App,
        overlay: ItemMorphView?,
        fromAreaX: Float,
        fromAreaY: Float,
        fromW: Float,
        targetPos: Int,
        remaining: OtherAppMorphInfo?,
        remainingPos: Int
    ) {
        val areaOrigin = drag.areaLoc()
        val destX = list.paddingLeft.toFloat()
        val destW = (list.width - list.paddingLeft - list.paddingRight).toFloat()
        val morph1 = overlay ?: drag.morphView(draggedApp, fromW.toInt())
        morph1.translationZ = dp(16).toFloat()
        val morphs = mutableListOf(
            ItemDragSession.Morph(morph1, fromAreaX, fromAreaY, fromW, destX, fromAreaY, destW, true)
        )
        if (remaining != null) {
            val morph2 = drag.morphView(remaining.app, remaining.startW.toInt())
            morphs.add(
                ItemDragSession.Morph(
                    morph2,
                    remaining.startX - areaOrigin[0],
                    remaining.startY - areaOrigin[1],
                    remaining.startW,
                    destX,
                    remaining.startY - areaOrigin[1],
                    destW,
                    true
                )
            )
        }
        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this)
                val holder = list.findViewHolderForAdapterPosition(targetPos)
                if (holder != null) {
                    val loc = IntArray(2)
                    holder.itemView.getLocationOnScreen(loc)
                    morphs.first().endY = (loc[1] - areaOrigin[1]).toFloat()
                }
                if (remaining != null) {
                    val remainingHolder = list.findViewHolderForAdapterPosition(remainingPos)
                    if (remainingHolder != null) {
                        val loc = IntArray(2)
                        remainingHolder.itemView.getLocationOnScreen(loc)
                        morphs.last().endY = (loc[1] - areaOrigin[1]).toFloat()
                    }
                }
                return true
            }
        })
        drag.play(morphs) {
            adapter.hiddenRowPosition = -1
            adapter.hiddenRowPosition2 = -1
            list.findViewHolderForAdapterPosition(targetPos)?.itemView?.alpha = 1f
            if (remaining != null) {
                list.findViewHolderForAdapterPosition(remainingPos)?.itemView?.alpha = 1f
            }
            pinnedManager.saveOrder()
            endListInteraction()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
