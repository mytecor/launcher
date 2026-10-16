package su.myt.home.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.myt.home.R
import su.myt.home.search.SearchEngine

@Composable
internal fun SearchRow(
    query: String,
    loading: Boolean,
    foreground: Color,
    muted: Color,
    inputColor: Color,
    accent: Color,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(
        targetValue = if (isFocused || query.isNotEmpty()) 5.dp else 2.dp,
        animationSpec = UiDpSpec,
        label = "searchElev"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RowShape,
        color = inputColor,
        shadowElevation = elevation
    ) {
        Row(
            Modifier.height(SearchBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = { onQueryChange(it.take(SearchEngine.MAX_QUERY_LENGTH)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                textStyle = TextStyle(color = foreground, fontSize = 18.sp),
                singleLine = true,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Crossfade(
                                targetState = stringResource(if (loading) R.string.loading_apps else R.string.search_hint),
                                animationSpec = UiFadeSpec,
                                label = "hintCrossfade"
                            ) { text ->
                                Text(text = text, color = muted, fontSize = 18.sp)
                            }
                        }
                        inner()
                    }
                }
            )
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(UiFadeSpec) + scaleIn(UiScaleSpring, initialScale = 0.5f),
                exit = fadeOut(UiFadeSpec) + scaleOut(UiFadeSpec, targetScale = 0.5f)
            ) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .padding(4.dp)
                        .size(48.dp)
                        .background(colorResource(R.color.launcher_row), CircleShape)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.clear_search),
                        tint = muted
                    )
                }
            }
        }
    }
}
