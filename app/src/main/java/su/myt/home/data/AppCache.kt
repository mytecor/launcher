package su.myt.home.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.AtomicFile
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import su.myt.home.model.App
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class AppCache(
    private val context: Context,
    private val cacheDir: File = File(context.filesDir, "app_cache")
) {
    private val appsJsonFile = File(cacheDir, "apps.json")
    private val appsAtomicFile = AtomicFile(appsJsonFile)
    private val iconsDir = File(cacheDir, "icons")

    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(4 * 1024, 32 * 1024)

    private val iconMemoryCache = object : LruCache<String, Drawable>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Drawable): Int {
            if (value is BitmapDrawable && value.bitmap != null) {
                return (value.bitmap.byteCount / 1024).coerceAtLeast(1)
            }
            return 32
        }
    }

    private val iconIoExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

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
                val cachedIcon = iconMemoryCache.get(appId)
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

    fun getMemoryIcon(app: App): Drawable? = iconMemoryCache.get(app.id)

    fun loadIcon(app: App, onLoaded: (Drawable) -> Unit): Drawable? {
        val mem = iconMemoryCache.get(app.id)
        if (mem != null) return mem

        iconIoExecutor.execute {
            val loaded = loadIconInternal(app)
            if (loaded != null) {
                iconMemoryCache.put(app.id, loaded)
                mainHandler.post { onLoaded(loaded) }
            }
        }
        return null
    }

    private fun loadIconInternal(app: App): Drawable? {
        val iconName = getIconFileName(app)
        val file = File(iconsDir, iconName)
        if (file.exists() && file.length() > 0L) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    return BitmapDrawable(context.resources, bitmap)
                }
            } catch (_: Throwable) {}
        }
        return try {
            context.packageManager.getActivityIcon(app.component)
        } catch (_: Exception) {
            null
        }
    }

    fun saveApps(apps: List<App>) {
        if (apps.isEmpty()) return
        memoryApps = apps
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            if (!iconsDir.exists()) iconsDir.mkdirs()

            val array = JSONArray()
            val activeIconNames = mutableSetOf<String>()

            for (app in apps) {
                val iconName = getIconFileName(app)
                activeIconNames.add(iconName)

                if (app.icon != null) {
                    iconMemoryCache.put(app.id, app.icon)
                    val iconFile = File(iconsDir, iconName)
                    if (!iconFile.exists() || iconFile.length() == 0L) {
                        saveIcon(app.icon, iconFile)
                    }
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

            iconsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in activeIconNames && !file.name.endsWith(".tmp")) {
                    file.delete()
                }
            }

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
        try {
            iconsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.contains(packageName)) {
                    file.delete()
                }
            }
            memoryApps = null
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            iconIoExecutor.shutdown()
        } catch (_: Exception) {}
    }

    private fun saveIcon(drawable: Drawable, file: File) {
        try {
            val maxDim = 144
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else maxDim
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else maxDim
            val (width, height) = if (w > maxDim || h > maxDim) {
                if (w >= h) {
                    maxDim to (h * maxDim / w).coerceAtLeast(1)
                } else {
                    (w * maxDim / h).coerceAtLeast(1) to maxDim
                }
            } else {
                w to h
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val oldBounds = drawable.copyBounds()
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            drawable.bounds = oldBounds

            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } catch (_: Throwable) {}
    }

    private fun getIconFileName(app: App): String {
        val raw = "${app.userSerial}_${app.component.flattenToString()}"
        val sanitized = raw.replace('/', '_').replace('$', '_').replace(':', '_')
        return "$sanitized.png"
    }
}
