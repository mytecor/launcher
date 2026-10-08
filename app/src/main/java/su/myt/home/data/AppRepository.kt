package su.myt.home.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import su.myt.home.model.App
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AppRepository(
    private val context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = notifyChanged()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = notifyChanged()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = notifyChanged()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = notifyChanged()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = notifyChanged()
        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) = notifyChanged()
        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) = notifyChanged()
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            notifyChanged()
        }
    }

    private var onAppsChangedListener: (() -> Unit)? = null

    fun startObserving(onChanged: () -> Unit) {
        this.onAppsChangedListener = onChanged
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
        try {
            context.unregisterReceiver(profileReceiver)
        } catch (_: Exception) {}
        try {
            context.getSystemService(LauncherApps::class.java)?.unregisterCallback(launcherAppsCallback)
        } catch (_: Exception) {}
        this.onAppsChangedListener = null
    }

    fun shutdown() {
        stopObserving()
        worker.shutdown()
    }

    private fun notifyChanged() {
        onAppsChangedListener?.invoke()
    }

    fun loadApps(onResult: (List<App>) -> Unit) {
        worker.execute {
            val apps = fetchApps()
            onResult(apps)
        }
    }

    private fun fetchApps(): List<App> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val packageManager = context.packageManager
        val packageName = context.packageName

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
                // Fallback to PackageManager
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
        }

        return finalApps.distinctBy { it.id }.sortedWith(
            compareBy<App> { it.label.lowercase() }
                .thenBy { it.userSerial }
                .thenBy { it.id }
        )
    }
}
