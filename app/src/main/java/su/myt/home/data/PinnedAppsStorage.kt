package su.myt.home.data

import android.content.Context
import android.content.SharedPreferences
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
        return if (saved != null) {
            try {
                val json = JSONArray(saved)
                val list = mutableListOf<MutableList<String>>()
                for (i in 0 until json.length()) {
                    val item = json.get(i)
                    if (item is JSONArray) {
                        val group = mutableListOf<String>()
                        for (j in 0 until item.length()) {
                            group.add(item.getString(j))
                        }
                        if (group.isNotEmpty()) list.add(group)
                    } else if (item is String) {
                        list.add(mutableListOf(item))
                    }
                }
                list
            } catch (_: Exception) {
                mutableListOf()
            }
        } else {
            val legacy = prefs.getStringSet("pinned", emptySet()) ?: emptySet()
            legacy.map { mutableListOf(it) }.toMutableList()
        }
    }

    override fun savePinnedOrder(pinnedOrder: List<List<String>>) {
        val json = JSONArray()
        val allIds = mutableSetOf<String>()
        for (group in pinnedOrder) {
            if (group.isEmpty()) continue
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
        prefs.edit()
            .putString("pinned_order", json.toString())
            .putStringSet("pinned", allIds)
            .apply()
    }
}
