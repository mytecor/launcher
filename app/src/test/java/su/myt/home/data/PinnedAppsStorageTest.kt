package su.myt.home.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PinnedAppsStorageTest {
    private val context = mock<Context>()
    private val preferences = mock<SharedPreferences>()

    init {
        whenever(context.getSharedPreferences("launcher", Context.MODE_PRIVATE)).thenReturn(preferences)
    }

    @Test
    fun malformedEntriesDoNotDiscardOtherPins() {
        whenever(preferences.getString("pinned_order", null)).thenReturn(
            """["first",["second",null,17,"third"],false,"fourth"]"""
        )
        assertEquals(
            listOf(listOf("first"), listOf("second", "third"), listOf("fourth")),
            PinnedAppsStorage(context).loadPinnedOrder()
        )
    }

    @Test
    fun duplicateAndBlankIdsAreRemovedAcrossGroups() {
        whenever(preferences.getString("pinned_order", null)).thenReturn(
            """["first",["first","", "second"]," ","second",["third","third"]]"""
        )
        assertEquals(
            listOf(listOf("first"), listOf("second"), listOf("third")),
            PinnedAppsStorage(context).loadPinnedOrder()
        )
    }

    @Test
    fun emptyCanonicalOrderDoesNotRestoreLegacyPins() {
        whenever(preferences.getString("pinned_order", null)).thenReturn("[]")
        whenever(preferences.getStringSet(eq("pinned"), any())).thenReturn(setOf("legacy"))
        assertEquals(emptyList<List<String>>(), PinnedAppsStorage(context).loadPinnedOrder())
    }

    @Test
    fun legacyPinsRemainAvailableForManagerMigration() {
        whenever(preferences.getStringSet(eq("pinned"), any())).thenReturn(linkedSetOf("legacy", ""))
        val storage = PinnedAppsStorage(context)
        assertFalse(storage.hasPinnedOrder())
        assertEquals(listOf(listOf("legacy")), storage.loadPinnedOrder())
    }

    @Test
    fun savingNormalizesGroupsAndLegacySetTogether() {
        val editor = mock<SharedPreferences.Editor>()
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(editor.putStringSet(any(), any())).thenReturn(editor)
        PinnedAppsStorage(context).savePinnedOrder(listOf(listOf("a", "a", "b"), listOf("b"), listOf(""), listOf("c")))

        val canonicalOrder = argumentCaptor<String>()
        verify(editor).putString(eq("pinned_order"), canonicalOrder.capture())
        assertEquals("""[["a","b"],"c"]""", JSONArray(canonicalOrder.firstValue).toString())
        verify(editor).putStringSet("pinned", setOf("a", "b", "c"))
        verify(editor).apply()
    }
}
