package su.myt.home.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class PinnedAppsStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun hasPinnedOrder(): Boolean = prefs.contains("pinned_order")

    fun loadPinnedOrder(): MutableList<String> {
        val saved = prefs.getString("pinned_order", null)
        return if (saved != null) {
            try {
                val json = JSONArray(saved)
                val list = mutableListOf<String>()
                for (i in 0 until json.length()) {
                    list.add(json.getString(i))
                }
                list
            } catch (_: Exception) {
                mutableListOf()
            }
        } else {
            val legacy = prefs.getStringSet("pinned", emptySet()) ?: emptySet()
            legacy.toMutableList()
        }
    }

    fun savePinnedOrder(pinnedOrder: List<String>) {
        val json = JSONArray(pinnedOrder)
        prefs.edit()
            .putString("pinned_order", json.toString())
            .putStringSet("pinned", pinnedOrder.toSet())
            .apply()
    }
}
