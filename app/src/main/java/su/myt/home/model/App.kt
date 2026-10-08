package su.myt.home.model

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

data class App(
    val label: String,
    val badgedLabel: String,
    val component: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val icon: Drawable? = null
) {
    val id: String
        get() = if (userSerial != 0L) "$userSerial:${component.flattenToString()}" else component.flattenToString()
}
