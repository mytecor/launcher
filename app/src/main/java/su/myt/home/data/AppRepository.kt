package su.myt.home.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import su.myt.home.model.App
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class AppRepository(
    context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val appCache: AppCache = AppCache(context)
) {
    private val context = context.applicationContext ?: context
    private var observing = false
    @Volatile private var closed = false
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = notifyChanged(packageName)
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = notifyChanged(packageName)
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = notifyChanged(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
            notifyChanged(*packageNames.orEmpty())
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
            notifyChanged(*packageNames.orEmpty())
        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) =
            notifyChanged(*packageNames.orEmpty())
        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) =
            notifyChanged(*packageNames.orEmpty())
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            notifyChanged()
        }
    }

    private var onAppsChangedListener: (() -> Unit)? = null

    fun startObserving(onChanged: () -> Unit) {
        if (closed) return
        this.onAppsChangedListener = onChanged
        if (observing) return
        observing = true
        try {
            context.getSystemService(LauncherApps::class.java)?.registerCallback(launcherAppsCallback)
        } catch (_: Exception) {}

        val profileFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
        }
        context.registerReceiver(profileReceiver, profileFilter)
    }

    fun stopObserving() {
        this.onAppsChangedListener = null
        if (!observing) return
        observing = false
        try {
            context.unregisterReceiver(profileReceiver)
        } catch (_: Exception) {}
        try {
            context.getSystemService(LauncherApps::class.java)?.unregisterCallback(launcherAppsCallback)
        } catch (_: Exception) {}
    }

    fun shutdown() {
        closed = true
        stopObserving()
        worker.shutdownNow()
        appCache.shutdown()
    }

    private fun notifyChanged(vararg packageNames: String?) {
        if (closed) return
        packageNames.filterNotNull().forEach(appCache::invalidatePackage)
        onAppsChangedListener?.invoke()
    }

    fun loadCachedApps(): List<App> = appCache.loadApps()

    fun loadIcon(app: App, onLoaded: (Drawable) -> Unit): Drawable? = appCache.loadIcon(app, onLoaded)

    fun loadApps(onResult: (List<App>) -> Unit) {
        if (closed) return
        try {
            worker.execute {
                if (closed) return@execute
                val apps = fetchApps()
                if (closed) return@execute
                appCache.saveApps(apps)
                if (!closed) onResult(apps)
            }
        } catch (_: RejectedExecutionException) {
            // A refresh can race with the Activity shutting down.
        }
    }

    private fun fetchApps(): List<App> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val packageManager = context.packageManager
        val packageName = context.packageName
        val currentUser = Process.myUserHandle()
        val currentUserSerial = userSerial(userManager, currentUser) ?: 0L

        val profiles = try {
            userManager?.userProfiles.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val loaded = mutableListOf<App>()
        var currentProfileLoaded = false

        if (launcherApps != null) {
            for (user in (profiles + currentUser).distinct()) {
                val userSerial = if (user == currentUser) currentUserSerial
                    else userSerial(userManager, user) ?: continue
                val activities = try {
                    launcherApps.getActivityList(null, user)
                } catch (_: Exception) {
                    // A locked/removed work profile must not discard other profiles.
                    continue
                }
                if (user == currentUser) currentProfileLoaded = true
                for (info in activities) {
                    if (info.componentName.packageName == packageName) continue
                    val label = try {
                        info.label.toString()
                    } catch (_: Exception) {
                        info.componentName.packageName
                    }
                    val badgedLabel = try {
                        packageManager.getUserBadgedLabel(label, user).toString()
                    } catch (_: Exception) {
                        label
                    }
                    loaded.add(
                        App(
                            label = label,
                            badgedLabel = badgedLabel,
                            component = info.componentName,
                            user = user,
                            userSerial = userSerial
                        )
                    )
                }
            }
        }

        if (!currentProfileLoaded) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = try {
                packageManager.queryIntentActivities(intent, 0)
            } catch (_: Exception) {
                // Preserve the last known snapshot when the system cannot be queried.
                return if (loaded.isEmpty()) appCache.loadApps() else loaded
            }
            loaded += activities
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    val label = try {
                        it.loadLabel(packageManager).toString()
                    } catch (_: Exception) {
                        it.activityInfo.packageName
                    }
                    val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                    App(
                        label = label,
                        badgedLabel = label,
                        component = component,
                        user = currentUser,
                        userSerial = currentUserSerial
                    )
                }
        }

        return loaded.distinctBy { it.id }.sortedWith(
            compareBy<App> { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.userSerial }
                .thenBy { it.id }
        )
    }

    private fun userSerial(userManager: UserManager?, user: UserHandle): Long? = try {
        userManager?.getSerialNumberForUser(user)?.takeIf { it >= 0L }
    } catch (_: Exception) {
        null
    }
}
