package su.myt.home.data

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import su.myt.home.model.App
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class IconCache(
    context: Context,
    private val iconsDir: File = File(context.filesDir, "app_cache/icons"),
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2),
    private val memoryCache: LruCache<String, Drawable> = createMemoryCache()
) {
    private val context = context.applicationContext ?: context

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLoads = mutableMapOf<String, MutableList<(Drawable) -> Unit>>()
    private val lock = Any()
    private var closed = false

    fun getMemoryIcon(appId: String): Drawable? = memoryCache.get(appId)

    fun loadIcon(app: App, onLoaded: (Drawable) -> Unit): Drawable? {
        synchronized(lock) {
            memoryCache.get(app.id)?.let { return it }
            if (closed) return null
            pendingLoads[app.id]?.let {
                it.add(onLoaded)
                return null
            }
            val callbacks = mutableListOf(onLoaded)
            pendingLoads[app.id] = callbacks
            try {
                ioExecutor.execute {
                    val loaded = loadIconFromDiskOrSystem(app) ?: run {
                        synchronized(lock) {
                            if (pendingLoads[app.id] === callbacks) pendingLoads.remove(app.id)
                        }
                        return@execute
                    }
                    synchronized(lock) {
                        // Invalidated requests must not repopulate the cache.
                        if (pendingLoads[app.id] !== callbacks) return@execute
                    }
                    // Drawing/compression stays off the UI/cache monitor.
                    val tempFile = prepareIconFile(app, loaded)
                    synchronized(lock) {
                        if (pendingLoads[app.id] !== callbacks) {
                            tempFile?.delete()
                            return@execute
                        }
                        if (tempFile != null && !tempFile.renameTo(File(iconsDir, getIconFileName(app)))) {
                            tempFile.delete()
                        }
                        memoryCache.put(app.id, loaded)
                    }
                    mainHandler.post {
                        val listeners = synchronized(lock) {
                            if (pendingLoads[app.id] !== callbacks) return@post
                            pendingLoads.remove(app.id)
                            callbacks.toList()
                        }
                        listeners.forEach { it(loaded) }
                    }
                }
            } catch (_: RejectedExecutionException) {
                pendingLoads.remove(app.id)
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
                    return bitmap.toDrawable(context.resources)
                }
            } catch (_: Exception) {}
        }
        val badgedIcon = try {
            context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(app.component.packageName, app.user)
                ?.firstOrNull { it.componentName == app.component }
                ?.getBadgedIcon(0)
        } catch (_: Exception) {
            null
        }
        if (badgedIcon != null || app.user != Process.myUserHandle()) return badgedIcon
        return try {
            context.packageManager.getActivityIcon(app.component)
        } catch (_: Exception) {
            null
        }
    }

    private fun prepareIconFile(app: App, drawable: Drawable): File? = try {
        val iconFile = File(iconsDir, getIconFileName(app))
        if (iconFile.exists() && iconFile.length() > 0L) {
            null
        } else {
            iconsDir.mkdirs()
            val tempFile = File.createTempFile(iconFile.name, ".tmp", iconsDir)
            if (writeIconBitmap(drawable, tempFile)) tempFile else {
                tempFile.delete()
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    fun cleanupUnusedIcons(activeIconNames: Set<String>) {
        iconsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in activeIconNames && !file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
    }

    fun invalidatePackage(packageName: String) {
        synchronized(lock) {
            val ids = memoryCache.snapshot().keys + pendingLoads.keys
            for (id in ids) {
                if (id.substringAfter(':').substringBefore('/') == packageName) {
                    memoryCache.remove(id)
                    pendingLoads.remove(id)
                }
            }
            iconsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.substringAfter('_').substringBefore('#') == packageName) {
                    file.delete()
                }
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            closed = true
            pendingLoads.clear()
            memoryCache.evictAll()
        }
        ioExecutor.shutdownNow()
    }

    fun getIconFileName(app: App): String {
        // '#' cannot occur in a Java package/class name, unlike '_' and '$'.
        return "${app.userSerial}_${app.component.flattenToString().replace('/', '#')}.png"
    }

    private fun writeIconBitmap(drawable: Drawable, file: File): Boolean = try {
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
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val source = drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable
        val oldBounds = source.copyBounds()
        try {
            source.setBounds(0, 0, canvas.width, canvas.height)
            source.draw(canvas)
        } finally {
            source.bounds = oldBounds
        }

        file.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    } catch (_: Exception) {
        false
    }
}

private fun createMemoryCache(): LruCache<String, Drawable> {
    val maxMemoryKb = Runtime.getRuntime().maxMemory() / 1024
    val cacheSizeKb = (maxMemoryKb / 8).coerceIn(4 * 1024L, 32 * 1024L).toInt()
    return object : LruCache<String, Drawable>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Drawable): Int {
            return if (value is BitmapDrawable) (value.bitmap.byteCount / 1024).coerceAtLeast(1) else 32
        }
    }
}
