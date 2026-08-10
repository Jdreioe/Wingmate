package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import kotlin.test.Test
import kotlin.test.assertTrue

class ObfValidatorIdScopeTest {
    @Test
    fun buttonIdsMayBeReusedOnDifferentBoards() {
        val boards = listOf(
            ParsedObfBoard("home.obf", board("home")),
            ParsedObfBoard("people.obf", board("people")),
        )

        assertTrue(ObfValidator().validate(boards, "home").isEmpty())
    }

    @Test
    fun duplicateButtonIdsWithinOneBoardRemainInvalid() {
        val board = board("home").copy(
            buttons = listOf(ObfButton(id = "1"), ObfButton(id = "1")),
        )

        val issues = ObfValidator().validate(listOf(ParsedObfBoard("home.obf", board)), "home")

        assertTrue(issues.any { it.message == "Duplicate board-local ID" })
    }

    @Test
    fun externalBoardLinksNeedNotBePackaged() {
        val board = board("home").copy(
            buttons = listOf(
                ObfButton(
                    id = "external",
                    loadBoard = ObfLoadBoard(
                        id = "remote-board",
                        url = "https://example.com/remote-board",
                    ),
                ),
            ),
        )

        assertTrue(
            ObfValidator().validate(listOf(ParsedObfBoard("home.obf", board)), "home").isEmpty(),
        )
    }

    private fun board(id: String) = ObfBoard(
        format = "open-board-0.1",
        id = id,
        buttons = listOf(ObfButton(id = "1")),
    )
}
