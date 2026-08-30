import io.github.jdreioe.wingmate.application.OBF_PHRASE_ID_EXTENSION
import io.github.jdreioe.wingmate.application.TYPING_ALL_PAGE_ID
import io.github.jdreioe.wingmate.application.TYPING_HISTORY_PAGE_ID
import io.github.jdreioe.wingmate.application.TypingScreenProjector
import io.github.jdreioe.wingmate.application.typingCategoryPageId
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypingScreenProjectorTest {
    @Test
    fun `projects flat categories and phrase media into runtime OBF`() {
        val template = TypingScreenProjector.defaultTemplate(columns = 3, now = 10)
        val phrases = listOf(
            Phrase(
                id = "tea",
                text = "Tea",
                name = "A cup of tea",
                backgroundColor = "#123456",
                imageUrl = "file:///tea.png",
                parentId = "drinks",
                createdAt = 1,
                recordingPath = "/tea.m4a",
            ),
            Phrase(id = "hello", text = "Hello", createdAt = 2),
        )

        val projection = TypingScreenProjector.project(
            template = template,
            phrases = phrases,
            categories = listOf(CategoryItem("drinks", "Drinks")),
            history = listOf(SaidText(id = 7, saidText = "Earlier", visibleInHistory = true)),
            columns = 3,
            includeHistory = true,
        )

        assertEquals(ScreenKind.Typing, projection.graph.boardSet.kind)
        assertEquals(
            listOf(TYPING_ALL_PAGE_ID, typingCategoryPageId("drinks"), TYPING_HISTORY_PAGE_ID),
            projection.pages.map { it.id },
        )
        val drinks = assertNotNull(projection.graph.boardsById[typingCategoryPageId("drinks")])
        val tea = drinks.buttons.single { it.extensions.containsKey(OBF_PHRASE_ID_EXTENSION) }
        assertEquals("Tea", tea.label)
        assertEquals("A cup of tea", tea.vocalization)
        assertNotNull(tea.imageId)
        assertNotNull(tea.soundId)
        assertEquals(1, drinks.images.size)
        assertEquals(1, drinks.sounds.size)
        assertTrue(drinks.grid!!.order.flatten().contains(tea.id))
    }

    @Test
    fun `generated phrase buttons are not added to the persisted template`() {
        val template = TypingScreenProjector.defaultTemplate(columns = 2, now = 10)
        val originalButtonIds = template.rootBoard!!.buttons.map { it.id }

        TypingScreenProjector.project(
            template,
            listOf(Phrase("p", "Private phrase", createdAt = 1)),
            emptyList(),
            emptyList(),
            columns = 2,
            includeHistory = false,
        )

        assertEquals(originalButtonIds, template.rootBoard!!.buttons.map { it.id })
        assertTrue(template.rootBoard!!.sounds.isEmpty())
        assertTrue(template.rootBoard!!.images.isEmpty())
    }
}
