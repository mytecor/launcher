package su.myt.home.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import su.myt.home.model.App
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class IconCache(
    private val context: Context,
    private val iconsDir: File = File(context.filesDir, "app_cache/icons"),
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)
) {
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(4 * 1024, 32 * 1024)

    private val memoryCache = object : LruCache<String, Drawable>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Drawable): Int {
            if (value is BitmapDrawable && value.bitmap != null) {
                return (value.bitmap.byteCount / 1024).coerceAtLeast(1)
            }
            return 32
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getMemoryIcon(appId: String): Drawable? = memoryCache.get(appId)

    fun putMemoryIcon(appId: String, drawable: Drawable) {
        memoryCache.put(appId, drawable)
    }

    fun loadIcon(app: App, onLoaded: (Drawable) -> Unit): Drawable? {
        val mem = memoryCache.get(app.id)
        if (mem != null) return mem

        ioExecutor.execute {
            val loaded = loadIconFromDiskOrSystem(app)
            if (loaded != null) {
                memoryCache.put(app.id, loaded)
                mainHandler.post { onLoaded(loaded) }
            }
        }
        return null
    }

    private fun loadIconFromDiskOrSystem(app: App): Drawable? {
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

    fun saveIconToDisk(app: App, drawable: Drawable) {
        val iconName = getIconFileName(app)
        val iconFile = File(iconsDir, iconName)
        if (!iconFile.exists() || iconFile.length() == 0L) {
            saveIconBitmap(drawable, iconFile)
        }
    }

    fun ensureIconsDirectory() {
        if (!iconsDir.exists()) iconsDir.mkdirs()
    }

    fun cleanupUnusedIcons(activeIconNames: Set<String>) {
        iconsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in activeIconNames && !file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
    }

    fun invalidatePackage(packageName: String) {
        try {
            iconsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.contains(packageName)) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            ioExecutor.shutdown()
        } catch (_: Exception) {}
    }

    fun getIconFileName(app: App): String {
        val raw = "${app.userSerial}_${app.component.flattenToString()}"
        val sanitized = raw.replace('/', '_').replace('$', '_').replace(':', '_')
        return "$sanitized.png"
    }

    private fun saveIconBitmap(drawable: Drawable, file: File) {
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
}
