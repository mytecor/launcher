package su.myt.home.ui

import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import su.myt.home.model.App

private val IconSize = 36.dp

@Composable
fun AppIcon(
    app: App,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?,
    modifier: Modifier = Modifier
) {
    val resources = LocalContext.current.resources
    val iconSizePx = with(LocalDensity.current) { IconSize.roundToPx().coerceAtLeast(1) }
    var bitmap by remember(app.id, iconSizePx) {
        val state = mutableStateOf<ImageBitmap?>(null)
        val seed = app.icon ?: loadIcon(app) { loaded ->
            loaded.toIconBitmapOrNull(iconSizePx, resources)?.let { state.value = it }
        }
        if (seed != null) {
            state.value = seed.toIconBitmapOrNull(iconSizePx, resources)
        }
        state
    }
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
