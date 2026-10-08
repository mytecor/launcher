package su.myt.home.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import su.myt.home.model.App

sealed class LaunchResult {
    data object Success : LaunchResult()
    data object NotFound : LaunchResult()
    data object Failed : LaunchResult()
}

class AppLauncher(private val context: Context) {
    fun launch(app: App): LaunchResult {
        return try {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            launcherApps?.startMainActivity(app.component, app.user, null, null)
            LaunchResult.Success
        } catch (_: ActivityNotFoundException) {
            LaunchResult.NotFound
        } catch (_: SecurityException) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(app.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
                LaunchResult.Success
            } catch (_: Exception) {
                LaunchResult.Failed
            }
        } catch (_: Exception) {
            LaunchResult.Failed
        }
    }
}
