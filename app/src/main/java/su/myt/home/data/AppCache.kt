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
    private val context: Context,
    private val cacheDir: File = File(context.filesDir, "app_cache"),
    private val iconCache: IconCache = IconCache(context, File(cacheDir, "icons"))
) {
    private val appsJsonFile = File(cacheDir, "apps.json")
    private val appsAtomicFile = AtomicFile(appsJsonFile)

    @Volatile
    private var memoryApps: List<App>? = null

    fun hasCache(): Boolean = appsJsonFile.exists() && appsJsonFile.length() > 0L

    fun loadApps(): List<App> {
        memoryApps?.let { return it }
        if (!hasCache()) return emptyList()

        return try {
            val bytes = appsAtomicFile.readFully()
            val jsonStr = String(bytes, Charsets.UTF_8)
            if (jsonStr.isBlank()) return emptyList()
            val array = JSONArray(jsonStr)
            val userManager = context.getSystemService(UserManager::class.java)
            val defaultUser = Process.myUserHandle()
            val userMap = mutableMapOf<Long, UserHandle>()
            val result = mutableListOf<App>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val componentStr = obj.optString("component")
                val component = ComponentName.unflattenFromString(componentStr) ?: continue
                val label = obj.optString("label", "")
                val badgedLabel = obj.optString("badgedLabel", label)
                val userSerial = obj.optLong("userSerial", 0L)
                val user = if (userSerial == 0L) {
                    defaultUser
                } else {
                    userMap.getOrPut(userSerial) {
                        try {
                            userManager?.getUserForSerialNumber(userSerial) ?: defaultUser
                        } catch (_: Exception) {
                            defaultUser
                        }
                    }
                }
                val appId = if (userSerial != 0L) "$userSerial:${component.flattenToString()}" else component.flattenToString()
                val cachedIcon = iconCache.getMemoryIcon(appId)
                result.add(
                    App(
                        label = label,
                        badgedLabel = badgedLabel,
                        component = component,
                        user = user,
                        userSerial = userSerial,
                        icon = cachedIcon
                    )
                )
            }
            memoryApps = result
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getMemoryIcon(app: App): Drawable? = iconCache.getMemoryIcon(app.id)

    fun loadIcon(app: App, onLoaded: (Drawable) -> Unit): Drawable? = iconCache.loadIcon(app, onLoaded)

    fun saveApps(apps: List<App>) {
        if (apps.isEmpty()) return
        memoryApps = apps
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            iconCache.ensureIconsDirectory()

            val array = JSONArray()
            val activeIconNames = mutableSetOf<String>()

            for (app in apps) {
                val iconName = iconCache.getIconFileName(app)
                activeIconNames.add(iconName)

                if (app.icon != null) {
                    iconCache.putMemoryIcon(app.id, app.icon)
                    iconCache.saveIconToDisk(app, app.icon)
                }

                val obj = JSONObject().apply {
                    put("label", app.label)
                    put("badgedLabel", app.badgedLabel)
                    put("component", app.component.flattenToString())
                    put("userSerial", app.userSerial)
                    put("icon", iconName)
                }
                array.put(obj)
            }

            iconCache.cleanupUnusedIcons(activeIconNames)

            var fos: FileOutputStream? = null
            try {
                fos = appsAtomicFile.startWrite()
                fos.write(array.toString().toByteArray(Charsets.UTF_8))
                appsAtomicFile.finishWrite(fos)
            } catch (e: Exception) {
                if (fos != null) {
                    appsAtomicFile.failWrite(fos)
                }
            }
        } catch (_: Exception) {}
    }

    fun invalidatePackage(packageName: String) {
        iconCache.invalidatePackage(packageName)
        memoryApps = null
    }

    fun shutdown() {
        iconCache.shutdown()
    }
}
