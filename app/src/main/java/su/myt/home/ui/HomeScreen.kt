@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package su.myt.home.ui

import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
    onCanGroup: (from: Int, to: Int) -> Boolean,
    onExtractToPosition: (group: Int, app: Int, target: Int) -> Unit,
    onExtractToGroup: (group: Int, app: Int, target: Int, slot: Int) -> Unit,
    onReorderInGroup: (group: Int, app: Int, slot: Int) -> Unit,
    onEmptyLongPress: () -> Unit,
    onDragActiveChange: (Boolean) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val listState = rememberLazyListState()

    val dragState = rememberHomeDragState(
        items = items,
        onMoveItem = onMoveItem,
        onGroupItems = onGroupItems,
        onCanGroup = onCanGroup,
        onExtractToPosition = onExtractToPosition,
        onExtractToGroup = onExtractToGroup,
        onReorderInGroup = onReorderInGroup,
        onDragActiveChange = onDragActiveChange
    )

    fun showIme() {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(Unit) { showIme() }
    BackHandler {
        onQueryChange("")
        showIme()
    }
    LaunchedEffect(query) {
        if (items.isNotEmpty()) listState.scrollToItem(0)
    }

    val foreground = colorResource(R.color.launcher_foreground)
    val muted = colorResource(R.color.launcher_muted)
    val rowColor = colorResource(R.color.launcher_row)
    val inputColor = colorResource(R.color.launcher_input)
    val accent = colorResource(R.color.launcher_accent)

    val emptyLongPress = rememberUpdatedState(onEmptyLongPress)
    val boxOrigin = rememberUpdatedState(dragState.boxRoot)
    val searching = rememberUpdatedState(isSearching)
    val latestDragMove = rememberUpdatedState<(Offset) -> Unit>({ dragState.onOverlayDrag(it) })
    val latestDragEnd = rememberUpdatedState({ dragState.onOverlayDragEnd() })

    Box(
        Modifier
            .fillMaxSize()
            .displayCutoutPadding()
            .onGloballyPositioned { dragState.boxRoot = it.positionInRoot() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset ->
                        if (searching.value || dragState.dragActive.value) return@detectTapGestures
                        val pointerY = offset.y + boxOrigin.value.y
                        if (!dragState.isOverItem(pointerY)) emptyLongPress.value()
                    }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!dragState.dragActive.value) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (!change.pressed) {
                            latestDragEnd.value()
                            change.consume()
                            continue
                        }
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            latestDragMove.value(delta)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val measuredListWidth = constraints.maxWidth.toFloat()
                val rowWidthPx = (measuredListWidth - 2f * with(LocalDensity.current) { ListHorizontalPadding.toPx() })
                    .coerceAtLeast(0f)
                SideEffect {
                    if (dragState.listWidth == 0f && measuredListWidth > 0f) {
                        dragState.listWidth = measuredListWidth
                    }
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    userScrollEnabled = dragState.drag == null,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .onGloballyPositioned {
                            val pos = it.positionInRoot()
                            dragState.listRootX = pos.x
                            dragState.listWidth = it.size.width.toFloat()
                        }
                        .padding(horizontal = ListHorizontalPadding),
                    contentPadding = PaddingValues(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
                ) {
                    val extracting = items.getOrNull(dragState.drag?.from ?: -1)?.isGroup == true
                    val visuals = pinnedVisuals(
                        count = items.size,
                        draggingFrom = if (dragState.revealDrop) -1 else (dragState.drag?.from ?: -1),
                        extracting = extracting && !dragState.revealDrop,
                        insertRow = when {
                            dragState.revealDrop -> -1
                            dragState.hoverGroup >= 0 -> -1
                            else -> dragState.insertRow
                        }
                    )
                    itemsIndexed(
                        visuals.asReversed(),
                        key = { _, visual ->
                            when (visual) {
                                PinnedVisual.Gap -> "insert-gap"
                                is PinnedVisual.Group ->
                                    items[visual.index].apps.map { it.id }.sorted().joinToString(";")
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
                                        .animateItem(
                                            fadeInSpec = snap(),
                                            fadeOutSpec = snap(),
                                            placementSpec = ItemPlacementSpring
                                        )
                                        .onGloballyPositioned {
                                            val pos = it.positionInRoot()
                                            dragState.gapBounds = RowBounds(pos.y, it.size.height.toFloat())
                                        },
                                    rowColor = rowColor,
                                    expandFromZero = false
                                )
                            }
                            is PinnedVisual.Group -> {
                                val index = visual.index
                                val group = items[index]
                                val grouping = dragState.hoverGroup == index
                                DisposableEffect(index) {
                                    onDispose { dragState.rowBounds.remove(index) }
                                }
                                val placement = Modifier.animateItem(
                                    fadeInSpec = snap(),
                                    fadeOutSpec = snap(),
                                    placementSpec = if (dragState.revealDrop) snap() else ItemPlacementSpring
                                )
                                val trackBounds = Modifier.onGloballyPositioned {
                                    val pos = it.positionInRoot()
                                    dragState.rowBounds[index] = RowBounds(pos.y, it.size.height.toFloat())
                                }
                                val app = group.apps.firstOrNull()
                                GroupRow(
                                    group = group,
                                    modifier = placement.then(trackBounds),
                                    grouping = grouping && !dragState.revealDrop,
                                    insertSlot = if (grouping && !dragState.revealDrop) dragState.hoverSlot else -1,
                                    draggingAppIndex = if (!dragState.revealDrop && dragState.drag?.from == index) {
                                        dragState.drag?.appIndex ?: -1
                                    } else {
                                        -1
                                    },
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
                                    dragEnabled = !isSearching && !grouping,
                                    onDragStart = { appIndex, dragged, root, width, grab ->
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        dragState.onDragStart(
                                            appIndex = appIndex,
                                            dragged = dragged,
                                            root = root,
                                            width = width,
                                            grab = grab,
                                            isGroup = group.isGroup,
                                            index = index
                                        )
                                    },
                                    onDrag = { },
                                    onDragEnd = { }
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
                modifier = Modifier.padding(SearchBarMargin)
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
