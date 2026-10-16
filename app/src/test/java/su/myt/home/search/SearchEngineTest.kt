package su.myt.home.search

import android.content.ComponentName
import android.os.UserHandle
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import su.myt.home.model.App
import java.util.Locale

class SearchEngineTest {
    private val engine = SearchEngine()

    private fun app(label: String, badgedLabel: String = label, serial: Long = 0L, component: String = label): App {
        val name = mock(ComponentName::class.java)
        `when`(name.flattenToString()).thenReturn("com.test/$component")
        return App(label, badgedLabel, name, mock(UserHandle::class.java), serial)
    }

    @Test
    fun bestMatchComesLastForTheBottomAnchoredList() {
        val exact = app("Telegram")
        val prefix = app("Telegram X")
        val substring = app("My Telegram")
        val unrelated = app("Camera")

        assertEquals(listOf(substring, prefix, exact), engine.search(listOf(exact, unrelated, prefix, substring), "telegram"))
    }

    @Test
    fun searchesBothLabelsAndKeepsTheirBestScore() {
        val work = app("Mail", "Work Mail", serial = 10L)

        assertEquals(10000, engine.matchScore(work, "mail"))
        assertEquals(10000, engine.matchScore(work, "work mail"))
        assertEquals(listOf(work), engine.search(listOf(work), "WORK MAIL"))
    }

    @Test
    fun emptyAndNormalizationOnlyQueriesDoNotReturnEveryApp() {
        val apps = listOf(app("Mail"))

        assertTrue(engine.search(apps, "   ").isEmpty())
        assertTrue(engine.search(apps, "\u0301").isEmpty())
    }

    @Test
    fun capsTheQueryBeforeMatching() {
        val label = "a".repeat(SearchEngine.MAX_QUERY_LENGTH)
        val exact = app(label)

        assertEquals(listOf(exact), engine.search(listOf(exact), label + "extra"))
    }

    @Test
    fun tieBreakingIsIndependentOfTheSystemLocaleAndInputOrder() {
        val first = app("Istanbul", "Match", component = "First")
        val second = app("Java", "Match", component = "Second")
        val personal = app("Mail", "Match")
        val work = app("Mail", "Match", serial = 10L)
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf(second, first), engine.search(listOf(first, second), "match"))
            assertEquals(listOf(work, personal), engine.search(listOf(personal, work), "match"))
            assertEquals(listOf(work, personal), engine.search(listOf(work, personal), "match"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
