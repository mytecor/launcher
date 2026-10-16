package su.myt.home.ui

import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import su.myt.home.model.App

private val IconSize = 36.dp
private data class IconBitmapKey(val appId: String, val sizePx: Int, val configuration: Configuration)
private class IconBitmapEntry {
    val bitmap: MutableState<ImageBitmap?> = mutableStateOf(null)
    var source: Drawable? = null

    fun update(drawable: Drawable, sizePx: Int, resources: Resources) {
        if (source === drawable && bitmap.value != null) return
        drawable.toIconBitmapOrNull(sizePx, resources)?.let {
            source = drawable
            bitmap.value = it
        }
    }
}
private val iconBitmapCache = LruCache<IconBitmapKey, IconBitmapEntry>(256)

@Composable
fun AppIcon(
    app: App,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?,
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current
    val iconSizePx = with(LocalDensity.current) { IconSize.roundToPx().coerceAtLeast(1) }
    val configuration = Configuration(LocalConfiguration.current)
    val key = IconBitmapKey(app.id, iconSizePx, configuration)
    val entry = remember(key) {
        iconBitmapCache.get(key) ?: IconBitmapEntry().also { iconBitmapCache.put(key, it) }
    }
    remember(entry, app.icon, app.id, loadIcon) {
        // Keep the last rendered bitmap while a refreshed drawable is loading.
        val seed = app.icon ?: loadIcon(app) { loaded ->
            entry.update(loaded, iconSizePx, resources)
        }
        if (seed != null) entry.update(seed, iconSizePx, resources)
        entry
    }
    val bitmap by entry.bitmap
    val shown = bitmap
    if (shown != null) {
        Image(
            bitmap = shown,
            contentDescription = null,
            modifier = modifier.size(IconSize),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier.size(IconSize))
    }
}

private fun Drawable.toIconBitmapOrNull(sizePx: Int, resources: Resources): ImageBitmap? {
    val copy = runCatching {
        constantState?.newDrawable(resources)?.mutate() ?: mutate()
    }.getOrElse { mutate() }
    return runCatching { copy.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
}
