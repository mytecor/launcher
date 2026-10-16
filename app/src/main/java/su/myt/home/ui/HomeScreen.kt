@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package su.myt.home.ui

import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import su.myt.home.R
import su.myt.home.model.App
import su.myt.home.model.AppGroup

@Composable
fun HomeScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    items: List<AppGroup>,
    loading: Boolean,
    isSearching: Boolean,
    isPinned: (App) -> Boolean,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?,
    onLaunch: (App) -> Unit,
    onTogglePin: (App) -> Unit,
    onMoveItem: (from: Int, to: Int) -> Boolean,
    onGroupItems: (from: Int, to: Int, slot: Int) -> Boolean,
    onCanGroup: (from: Int, to: Int, extracting: Boolean) -> Boolean,
    onExtractToPosition: (group: Int, app: Int, target: Int) -> Unit,
    onExtractToGroup: (group: Int, app: Int, target: Int, slot: Int) -> Unit,
    onReorderInGroup: (group: Int, app: Int, slot: Int) -> Unit,
    onEmptyLongPress: () -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
    onOpenNotifications: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val listState = rememberLazyListState()

    var collapsingRow by remember(query) { mutableStateOf<Pair<Int, String>?>(null) }
    val wrappedOnGroupItems: (Int, Int, Int) -> Boolean = { from, to, slot ->
        val fromGroup = items.getOrNull(from)
        val originId = fromGroup?.takeUnless { it.isGroup }?.id
        val grouped = onGroupItems(from, to, slot)
        if (grouped && originId != null) {
            collapsingRow = Pair(from, originId)
        }
        grouped
    }

    val dragState = rememberHomeDragState(
        items = items,
        onMoveItem = onMoveItem,
        onGroupItems = wrappedOnGroupItems,
        onCanGroup = onCanGroup,
        onExtractToPosition = onExtractToPosition,
        onExtractToGroup = onExtractToGroup,
        onReorderInGroup = onReorderInGroup,
        onDragActiveChange = onDragActiveChange
    )

    val density = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 48.dp.toPx() } }
    val flingThresholdPx = remember(density) { with(density) { 400.dp.toPx() } }
    val latestOpenNotifications = rememberUpdatedState(onOpenNotifications)
    val latestSearching = rememberUpdatedState(isSearching)

    val notificationSwipeConnection = remember(density, keyboard, dragState, listState) {
        NotificationSwipeConnection(
            isSearching = { latestSearching.value },
            isDragActive = { dragState.dragActive },
            isAtTop = { !listState.canScrollForward },
            swipeThresholdPx = { swipeThresholdPx },
            flingThresholdPx = { flingThresholdPx },
            onOpenNotifications = {
                keyboard?.hide()
                latestOpenNotifications.value()
            }
        )
    }
    LaunchedEffect(isSearching, dragState.dragActive) {
        if (isSearching || dragState.dragActive) notificationSwipeConnection.reset()
    }

    fun showIme() {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(Unit) { showIme() }
    BackHandler {
        onQueryChange("")
        showIme()
    }
    LaunchedEffect(query, isSearching, dragState) {
        dragState.onDragCancel()
        if (items.isNotEmpty()) listState.scrollToItem(0)
    }

    val foreground = colorResource(R.color.launcher_foreground)
    val muted = colorResource(R.color.launcher_muted)
    val rowColor = colorResource(R.color.launcher_row)
    val inputColor = colorResource(R.color.launcher_input)
    val accent = colorResource(R.color.launcher_accent)

    val emptyLongPress = rememberUpdatedState(onEmptyLongPress)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { dragState.boxRoot = it.positionInRoot() }
            .pointerInput(dragState) {
                detectTapGestures(
                    onLongPress = { offset ->
                        if (latestSearching.value || dragState.dragActive) return@detectTapGestures
                        val pointerY = offset.y + dragState.boxRoot.y
                        if (!dragState.isOverItem(pointerY)) emptyLongPress.value()
                    }
                )
            }
            .pointerInput(dragState) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            dragState.onPointerChanges(event.changes)
                        }
                    }
                } finally {
                    dragState.onDragCancel()
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        ) {
            val searchBarOverlap = SearchBarMargin + SearchBarHeight / 2
            val topContentPadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 16.dp
            val bottomContentPadding = SearchBarHeight / 2 + SearchBarMargin

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = searchBarOverlap)
                    .nestedScroll(notificationSwipeConnection)
            ) {
                val measuredListWidth = constraints.maxWidth.toFloat()
                val rowWidthPx = (measuredListWidth - 2f * with(LocalDensity.current) { ListHorizontalPadding.toPx() })
                    .coerceAtLeast(0f)
                SideEffect {
                    if (dragState.listWidth == 0f && measuredListWidth > 0f) {
                        dragState.listWidth = measuredListWidth
                    }
                }
                val extracting = items.getOrNull(dragState.drag?.from ?: -1)?.isGroup == true
                var closingInsertGapRow by remember(isSearching, dragState.drag != null, dragState.revealDrop) { mutableStateOf<Int?>(null) }
                val rawInsertRow = when {
                    dragState.revealDrop -> -1
                    dragState.hoverGroup >= 0 -> -1
                    else -> dragState.insertRow
                }
                if (rawInsertRow >= 0 && extracting) {
                    closingInsertGapRow = rawInsertRow
                }
                val isClosingGap = rawInsertRow < 0 && closingInsertGapRow != null
                val effectiveInsertRow = if (rawInsertRow >= 0) rawInsertRow else (closingInsertGapRow ?: -1)

                val visuals = pinnedVisuals(
                    count = items.size,
                    draggingFrom = if (dragState.revealDrop) -1 else (dragState.drag?.from ?: -1),
                    extracting = (extracting || isClosingGap) && !dragState.revealDrop,
                    insertRow = effectiveInsertRow,
                    collapsingRow = collapsingRow
                )

                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    userScrollEnabled = dragState.drag == null,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            val pos = it.positionInRoot()
                            dragState.listRootX = pos.x
                            dragState.listWidth = it.size.width.toFloat()
                        },
                    contentPadding = PaddingValues(
                        start = ListHorizontalPadding,
                        top = topContentPadding,
                        end = ListHorizontalPadding,
                        bottom = bottomContentPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(GroupSpacing, Alignment.Bottom)
                ) {
                    itemsIndexed(
                        visuals.asReversed(),
                        key = { _, visual ->
                            when (visual) {
                                PinnedVisual.Gap -> "insert-gap"
                                is PinnedVisual.CollapsingRow -> "collapsing-${visual.id}"
                                is PinnedVisual.Group -> items.getOrNull(visual.index)?.id ?: "empty-${visual.index}"
                            }
                        }
                    ) { _, visual ->
                        when (visual) {
                            PinnedVisual.Gap -> {
                                DisposableEffect(Unit) {
                                    onDispose { dragState.gapBounds = null }
                                }
                                AnimatedInsertGap(
                                    modifier = Modifier
                                        .onGloballyPositioned {
                                            if (!isClosingGap) {
                                                val pos = it.positionInRoot()
                                                dragState.gapBounds = RowBounds(pos.y, it.size.height.toFloat())
                                            }
                                        },
                                    rowColor = rowColor,
                                    isClosing = isClosingGap,
                                    onCollapseComplete = {
                                        closingInsertGapRow = null
                                    }
                                )
                            }
                            is PinnedVisual.CollapsingRow -> {
                                val heightAnim = remember(visual.id) {
                                    Animatable(with(density) { RowHeight.toPx() })
                                }
                                LaunchedEffect(visual.id) {
                                    heightAnim.animateTo(0f, SlotSpring)
                                    if (collapsingRow?.second == visual.id) {
                                        collapsingRow = null
                                    }
                                }
                                val currentHeight = with(density) { heightAnim.value.toDp() }
                                val progress = (heightAnim.value / with(density) { RowHeight.toPx() }).coerceIn(0f, 1f)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(currentHeight),
                                    shape = RowShape,
                                    color = rowColor.copy(alpha = 0.25f * progress)
                                ) {}
                            }
                            is PinnedVisual.Group -> {
                                val index = visual.index
                                val group = items[index]
                                val grouping = dragState.hoverGroup == index
                                DisposableEffect(index) {
                                    onDispose { dragState.rowBounds.remove(index) }
                                }
                                val placement = Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = ItemPlacementSpring
                                )
                                val trackBounds = Modifier.onGloballyPositioned {
                                    val pos = it.positionInRoot()
                                    dragState.rowBounds[index] = RowBounds(pos.y, it.size.height.toFloat())
                                }
                                val app = group.apps.firstOrNull()
                                val draggedAppId = if (dragState.revealDrop) null else dragState.drag?.app?.id
                                val draggingAppIndex = if (draggedAppId != null) {
                                    group.apps.indexOfFirst { it.id == draggedAppId }
                                } else {
                                    -1
                                }
                                GroupRow(
                                    group = group,
                                    modifier = placement.then(trackBounds),
                                    grouping = grouping && !dragState.revealDrop,
                                    insertSlot = if (grouping && !dragState.revealDrop) dragState.hoverSlot else -1,
                                    draggingAppIndex = draggingAppIndex,
                                    rowWidthPx = rowWidthPx,
                                    spacingPx = dragState.groupSpacingPx,
                                    rowColor = rowColor,
                                    foreground = foreground,
                                    accent = accent,
                                    showStar = isSearching && app != null && isPinned(app),
                                    loadIcon = loadIcon,
                                    onLaunch = onLaunch,
                                    onLongClick = if (isSearching && app != null) {
                                        { onTogglePin(app) }
                                    } else {
                                        null
                                    },
                                    dragEnabled = !isSearching && !dragState.dragActive,
                                    onDragStart = { appIndex, dragged, root, grabX, pointerId ->
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        dragState.onDragStart(
                                            appIndex = appIndex,
                                            dragged = dragged,
                                            root = root,
                                            grabX = grabX,
                                            index = index,
                                            pointerId = pointerId
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            SearchRow(
                query = query,
                loading = loading,
                foreground = foreground,
                muted = muted,
                inputColor = inputColor,
                accent = accent,
                focusRequester = focusRequester,
                onQueryChange = onQueryChange,
                onSearch = { items.lastOrNull()?.apps?.firstOrNull()?.let(onLaunch) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(SearchBarMargin)
            )
        }

        dragState.drag?.let { overlay ->
            DragFloatingOverlay(
                overlay = overlay,
                dragState = dragState,
                rowColor = rowColor,
                foreground = foreground,
                loadIcon = loadIcon
            )
        }
    }
}
