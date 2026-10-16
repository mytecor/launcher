package su.myt.home.data

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import su.myt.home.model.App
import java.io.File
import java.io.FileOutputStream

class AppCache(
    context: Context,
    private val cacheDir: File = File(context.filesDir, "app_cache"),
    private val iconCache: IconCache = IconCache(context, File(cacheDir, "icons"))
) {
    private val context = context.applicationContext ?: context
    private val appsJsonFile = File(cacheDir, "apps.json")
    private val appsAtomicFile = AtomicFile(appsJsonFile)

    private var memoryApps: List<App>? = null

    @Synchronized
    fun loadApps(): List<App> {
        memoryApps?.let { return it }

        return try {
            val bytes = appsAtomicFile.readFully()
            val jsonStr = String(bytes, Charsets.UTF_8)
            if (jsonStr.isBlank()) return emptyList()
            val array = JSONArray(jsonStr)
            val userManager = context.getSystemService(UserManager::class.java)
            val defaultUser = Process.myUserHandle()
            val userMap = mutableMapOf<Long, UserHandle?>()
            val result = mutableListOf<App>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val componentStr = obj.optString("component")
                val component = ComponentName.unflattenFromString(componentStr) ?: continue
                val label = obj.optString("label", "")
                val badgedLabel = obj.optString("badgedLabel", label)
                val userSerial = obj.optLong("userSerial", 0L)
                if (userSerial < 0L) continue
                val user = if (userSerial == 0L) {
                    defaultUser
                } else {
                    if (!userMap.containsKey(userSerial)) {
                        userMap[userSerial] = try {
                            userManager?.getUserForSerialNumber(userSerial)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    userMap[userSerial] ?: continue
                }
                val app = App(
                    label = label,
                    badgedLabel = badgedLabel,
                    component = component,
                    user = user,
                    userSerial = userSerial
                )
                result.add(app.copy(icon = iconCache.getMemoryIcon(app.id)))
            }
            memoryApps = result
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadIcon(app: App, onLoaded: (Drawable) -> Unit): Drawable? = iconCache.loadIcon(app, onLoaded)

    @Synchronized
    fun saveApps(apps: List<App>) {
        memoryApps = apps.toList()
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val array = JSONArray()
            val activeIconNames = mutableSetOf<String>()

            for (app in apps) {
                val iconName = iconCache.getIconFileName(app)
                activeIconNames.add(iconName)

                val obj = JSONObject().apply {
                    put("label", app.label)
                    put("badgedLabel", app.badgedLabel)
                    put("component", app.component.flattenToString())
                    put("userSerial", app.userSerial)
                }
                array.put(obj)
            }

            var fos: FileOutputStream? = null
            try {
                fos = appsAtomicFile.startWrite()
                fos.write(array.toString().toByteArray(Charsets.UTF_8))
                appsAtomicFile.finishWrite(fos)
                iconCache.cleanupUnusedIcons(activeIconNames)
            } catch (e: Exception) {
                if (fos != null) {
                    appsAtomicFile.failWrite(fos)
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun invalidatePackage(packageName: String) {
        iconCache.invalidatePackage(packageName)
        memoryApps = null
    }

    fun shutdown() {
        iconCache.shutdown()
    }
}
