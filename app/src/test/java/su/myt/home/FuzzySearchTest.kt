package su.myt.home

import org.junit.Assert.*
import org.junit.Test

class FuzzySearchTest {
    @Test fun exactOutranksPrefixAndSubsequence() {
        assertTrue(FuzzySearch.score("Telegram", "telegram")!! > FuzzySearch.score("Telegram X", "telegram")!!)
        assertTrue(FuzzySearch.score("Telegram", "tel")!! > FuzzySearch.score("The Elder", "tel")!!)
    }
    @Test fun supportsMissingLettersAndTypos() {
        assertNotNull(FuzzySearch.score("Telegram", "tlgrm"))
        assertNotNull(FuzzySearch.score("Telegram", "telegarm"))
        assertNotNull(FuzzySearch.score("Камера", "камреа"))
    }
    @Test fun normalizesCaseAndAccents() {
        assertEquals(10000, FuzzySearch.score("ЁЖ", "еж"))
        assertEquals(10000, FuzzySearch.score("Café", " CAFE "))
    }
    @Test fun rejectsUnrelatedAndShortTypos() {
        assertNull(FuzzySearch.score("Camera", "xyz"))
        assertNull(FuzzySearch.score("Maps", "mx"))
    }
}
