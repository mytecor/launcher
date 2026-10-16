package su.myt.home

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import su.myt.home.data.AppLauncher
import su.myt.home.data.AppRepository
import su.myt.home.data.LaunchResult
import su.myt.home.data.PinnedAppsStorage
import su.myt.home.model.App
import su.myt.home.model.AppGroup
import su.myt.home.pinned.PinnedAppsManager
import su.myt.home.search.SearchEngine
import su.myt.home.ui.HomeScreen
import su.myt.home.ui.HomeTheme

class MainActivity : ComponentActivity() {

    private val appRepository by lazy { AppRepository(this) }
    private val appLauncher by lazy { AppLauncher(this) }
    private val pinnedManager by lazy { PinnedAppsManager(PinnedAppsStorage(this)) }
    private val searchEngine = SearchEngine()

    private var query by mutableStateOf("")
    // App equality omits drawables, but a refresh must still replace the icon sources.
    private var allApps by mutableStateOf<List<App>>(emptyList(), referentialEqualityPolicy())
    private var loading by mutableStateOf(true)
    private var pinRevision by mutableIntStateOf(0)
    private var dragActive = false
    private var pendingApps: List<App>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val cached = appRepository.loadCachedApps()
        if (cached.isNotEmpty()) {
            allApps = cached
            loading = false
        }
        query = savedInstanceState?.getString("query").orEmpty()

        setContent {
            val isSearching = query.trim().isNotEmpty()
            val items = remember(allApps, query, pinRevision) {
                if (isSearching) {
                    searchEngine.search(allApps, query).map { AppGroup(mutableListOf(it)) }.toMutableList()
                } else {
                    pinnedManager.getPinnedGroups(allApps)
                }
            }
            HomeTheme {
                HomeScreen(
                    query = query,
                    onQueryChange = { query = it },
                    items = items,
                    loading = loading,
                    isSearching = isSearching,
                    isPinned = { pinnedManager.isPinned(it) },
                    loadIcon = { app, onLoaded -> appRepository.loadIcon(app, onLoaded) },
                    onLaunch = ::launch,
                    onTogglePin = { app ->
                        val added = pinnedManager.togglePin(app)
                        pinRevision++
                        Toast.makeText(
                            this,
                            getString(if (added) R.string.app_pinned else R.string.app_unpinned, app.badgedLabel),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onMoveItem = { from, to ->
                        updatePins { pinnedManager.moveItem(items, from, to) }
                    },
                    onGroupItems = { from, to, slot ->
                        updatePins { pinnedManager.groupItems(items, from, to, slot) }
                    },
                    onCanGroup = { from, to, extracting -> pinnedManager.canGroup(items, from, to, extracting) },
                    onExtractToPosition = { group, app, target ->
                        updatePins { pinnedManager.extractAppToPosition(items, group, app, target) }
                    },
                    onExtractToGroup = { group, app, target, slot ->
                        updatePins { pinnedManager.extractAppToGroup(items, group, app, target, slot) }
                    },
                    onReorderInGroup = { group, app, slot ->
                        updatePins { pinnedManager.moveAppInGroup(items, group, app, slot) }
                    },
                    onEmptyLongPress = ::openWallpaperPicker,
                    onDragActiveChange = ::onDragActiveChange,
                    onOpenNotifications = ::openNotificationShade
                )
            }
        }

        appRepository.startObserving { reloadApps() }
    }

    override fun onResume() {
        super.onResume()
        showKeyboard()
        reloadApps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        query = ""
        showKeyboard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        appRepository.shutdown()
        super.onDestroy()
    }

    private fun showKeyboard() {
        window.insetsController?.show(WindowInsets.Type.ime())
        getSystemService(InputMethodManager::class.java)?.showSoftInput(window.decorView, 0)
    }

    private fun reloadApps() {
        appRepository.loadApps { loaded ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (dragActive) pendingApps = loaded else applyLoadedApps(loaded)
            }
        }
    }

    private fun applyLoadedApps(apps: List<App>) {
        pinnedManager.syncWithLoadedApps(apps)
        allApps = apps
        loading = false
        pinRevision++
    }

    private fun onDragActiveChange(active: Boolean) {
        dragActive = active
        if (!active) {
            pendingApps?.let(::applyLoadedApps)
            pendingApps = null
        }
    }

    private inline fun updatePins(change: () -> Boolean): Boolean {
        if (query.isNotBlank()) return false
        return change().also { changed ->
            if (changed) pinRevision++
        }
    }

    private fun launch(app: App) {
        when (appLauncher.launch(app)) {
            is LaunchResult.Success -> query = ""
            is LaunchResult.NotFound -> {
                Toast.makeText(this, getString(R.string.app_unavailable), Toast.LENGTH_SHORT).show()
            }
            is LaunchResult.Failed -> {
                Toast.makeText(this, getString(R.string.launch_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openWallpaperPicker() {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.set_wallpaper))
        try {
            startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.wallpaper_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotificationShade() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        try {
            @SuppressLint("WrongConstant")
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val method = statusBarManager.getMethod("expandNotificationsPanel")
            method.isAccessible = true
            method.invoke(statusBarService)
        } catch (_: Exception) {
        }
    }
}
