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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is App) return false
        return userSerial == other.userSerial &&
                component == other.component &&
                label == other.label &&
                badgedLabel == other.badgedLabel
    }

    override fun hashCode(): Int {
        var result = component.hashCode()
        result = 31 * result + userSerial.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + badgedLabel.hashCode()
        return result
    }
}
