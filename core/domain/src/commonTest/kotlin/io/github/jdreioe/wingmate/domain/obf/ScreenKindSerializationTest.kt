package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenKindSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `screen data written before kind remains a user screen`() {
        val decoded = json.decodeFromString<ObfBoardSet>(
            """{"id":"set","name":"My screen","rootBoardId":"root","createdAt":1,"updatedAt":2}"""
        )

        assertEquals(ScreenKind.User, decoded.kind)
    }

    @Test
    fun `typing kind survives serialization`() {
        val source = ObfBoardSet(
            id = "typing",
            name = "Typing",
            rootBoardId = "template",
            kind = ScreenKind.Typing,
            createdAt = 1,
            updatedAt = 2,
        )

        assertEquals(
            ScreenKind.Typing,
            json.decodeFromString<ObfBoardSet>(json.encodeToString(source)).kind,
        )
    }
}
