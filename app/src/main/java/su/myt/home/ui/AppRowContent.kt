package su.myt.home.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.myt.home.R
import su.myt.home.model.App

private val StarSlotSize = 36.dp
private val StarSize = 20.dp

@Composable
internal fun AppRowContent(
    app: App,
    showStar: Boolean,
    foreground: Color,
    accent: Color = foreground,
    loadIcon: (App, (Drawable) -> Unit) -> Drawable?,
    compact: Boolean = false
) {
    val description = if (showStar) {
        stringResource(R.string.pinned_description, app.badgedLabel)
    } else {
        app.badgedLabel
    }
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 0.dp else 16.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app, loadIcon)
        if (!compact) {
            Text(
                text = app.label,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                color = foreground,
                fontSize = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(
            visible = showStar && !compact,
            enter = fadeIn(UiFadeSpec) + scaleIn(UiScaleSpring, initialScale = 0.4f),
            exit = fadeOut(UiFadeSpec) + scaleOut(UiFadeSpec, targetScale = 0.4f)
        ) {
            Box(
                modifier = Modifier.size(StarSlotSize),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(StarSize)
                )
            }
        }
    }
}
