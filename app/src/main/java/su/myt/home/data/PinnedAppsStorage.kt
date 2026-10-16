package su.myt.home.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray

interface PinnedStorage {
    fun hasPinnedOrder(): Boolean
    fun loadPinnedOrder(): MutableList<MutableList<String>>
    fun savePinnedOrder(pinnedOrder: List<List<String>>)
}

class PinnedAppsStorage(context: Context) : PinnedStorage {
    private val prefs: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    override fun hasPinnedOrder(): Boolean = prefs.contains("pinned_order")

    override fun loadPinnedOrder(): MutableList<MutableList<String>> {
        val saved = prefs.getString("pinned_order", null)
        val groups = if (saved != null) {
            try {
                val json = JSONArray(saved)
                List(json.length()) { i ->
                    when (val item = json.opt(i)) {
                        is JSONArray -> List(item.length()) { item.opt(it) }.filterIsInstance<String>()
                        is String -> listOf(item)
                        else -> emptyList()
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            val legacy = prefs.getStringSet("pinned", emptySet()) ?: emptySet()
            legacy.map { listOf(it) }
        }
        return normalizeOrder(groups).map { it.toMutableList() }.toMutableList()
    }

    override fun savePinnedOrder(pinnedOrder: List<List<String>>) {
        val json = JSONArray()
        val allIds = mutableSetOf<String>()
        for (group in normalizeOrder(pinnedOrder)) {
            if (group.size == 1) {
                json.put(group[0])
                allIds.add(group[0])
            } else {
                val groupArray = JSONArray()
                for (id in group) {
                    groupArray.put(id)
                    allIds.add(id)
                }
                json.put(groupArray)
            }
        }
        prefs.edit {
            putString("pinned_order", json.toString())
            putStringSet("pinned", allIds)
        }
    }

    private fun normalizeOrder(groups: List<List<String>>): List<List<String>> {
        val seen = mutableSetOf<String>()
        return groups.mapNotNull { group ->
            group.filter { it.isNotBlank() && seen.add(it) }.takeIf { it.isNotEmpty() }
        }
    }
}
