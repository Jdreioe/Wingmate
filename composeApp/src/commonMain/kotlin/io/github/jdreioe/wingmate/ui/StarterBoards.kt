package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import org.jetbrains.compose.resources.InternalResourceApi
import wingmatekmp.composeapp.generated.resources.Res

private data class StarterBoardBundle(
    val name: String,
    val fileNames: List<String>
)

private fun starterBoardBundle(languageTag: String): StarterBoardBundle =
    if (languageTag.startsWith("da", ignoreCase = true)) {
        StarterBoardBundle(
            name = "Starttavler",
            fileNames = listOf(
                "starter_da_hilsner",
                "starter_da_kernetavle",
                "starter_da_mad_og_drikke",
                "starter_da_foelelser"
            )
        )
    } else {
        StarterBoardBundle(
            name = "Starter Boards",
            fileNames = listOf("starter_en_hello", "starter_en_goodbye")
        )
    }

@OptIn(InternalResourceApi::class)
internal suspend fun restoreStarterBoards(
    languageTag: String,
    obfParser: ObfParser,
    boardSetUseCase: BoardSetUseCase
): ObfBoardSet? {
    val bundle = starterBoardBundle(languageTag)
    val boards = buildList<ObfBoard> {
        bundle.fileNames.forEach { fileName ->
            val bytes = runCatching { Res.readBytes("files/$fileName.obf") }.getOrNull()
                ?: return@forEach
            obfParser.parseBoard(bytes.decodeToString()).getOrNull()?.let(::add)
        }
    }
    if (boards.isEmpty()) return null
    return boardSetUseCase.createBoardSetFromBoards(bundle.name, boards)
}
