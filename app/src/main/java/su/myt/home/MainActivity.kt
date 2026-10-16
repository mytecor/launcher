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
    private var allApps by mutableStateOf<List<App>>(emptyList())
    private var loading by mutableStateOf(true)
    private var pinEpoch by mutableIntStateOf(0)
    private var listInteraction = false
    private var pendingReload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val cached = appRepository.loadCachedApps()
        if (cached.isNotEmpty()) {
            allApps = cached
            loading = false
            pinnedManager.syncWithLoadedApps(cached)
        }
        query = savedInstanceState?.getString("query").orEmpty()

        setContent {
            val isSearching = query.trim().isNotEmpty()
            val items = if (!isSearching) {
                pinnedManager.getPinnedGroups(allApps)
            } else {
                searchEngine.search(allApps, query).map { AppGroup(mutableListOf(it)) }.toMutableList()
            }
            pinEpoch
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
                        pinEpoch++
                        Toast.makeText(
                            this,
                            getString(if (added) R.string.app_pinned else R.string.app_unpinned, app.badgedLabel),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onMoveItem = { from, to ->
                        val moved = pinnedManager.moveItem(items, from, to)
                        if (moved) {
                            pinnedManager.saveOrder()
                            pinEpoch++
                        }
                        moved
                    },
                    onGroupItems = { from, to, slot ->
                        val grouped = pinnedManager.groupItems(items, from, to, slot)
                        if (grouped) pinEpoch++
                        grouped
                    },
                    onCanGroup = { from, to -> pinnedManager.canGroup(items, from, to) },
                    onExtractToPosition = { group, app, target ->
                        if (pinnedManager.extractAppToPosition(items, group, app, target)) pinEpoch++
                    },
                    onExtractToGroup = { group, app, target, slot ->
                        if (pinnedManager.extractAppToGroup(items, group, app, target, slot)) pinEpoch++
                    },
                    onReorderInGroup = { group, app, slot ->
                        if (pinnedManager.moveAppInGroup(items, group, app, slot)) pinEpoch++
                    },
                    onEmptyLongPress = ::openWallpaperPicker,
                    onDragActiveChange = { active ->
                        if (active) {
                            listInteraction = true
                        } else {
                            listInteraction = false
                            if (pendingReload) {
                                pendingReload = false
                                reloadApps()
                            }
                        }
                    },
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
                if (listInteraction) {
                    pendingReload = true
                    return@runOnUiThread
                }
                val appsChanged = allApps != loaded
                pinnedManager.syncWithLoadedApps(loaded)
                allApps = loaded
                val wasLoading = loading
                loading = false
                if (appsChanged || wasLoading) pinEpoch++
            }
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
