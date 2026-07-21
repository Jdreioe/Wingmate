package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import org.jetbrains.compose.resources.InternalResourceApi
import wingmatekmp.composeapp.generated.resources.Res

internal data class StarterBoardBundle(
    val name: String,
    val fileNames: List<String>
)

internal fun starterBoardFiles(languageTag: String): List<String>? = when {
    languageTag.startsWith("da", ignoreCase = true) -> listOf(
        "starter_da_hjem",
        "starter_da_core24",
        "starter_da_universal_core",
        "starter_da_behov",
        "starter_da_hilsner",
        "starter_da_kernetavle",
        "starter_da_mad_og_drikke",
        "starter_da_foelelser"
    )
    languageTag.startsWith("en", ignoreCase = true) -> listOf(
        "starter_en_home",
        "starter_en_core24",
        "starter_en_universal_core",
        "starter_en_needs",
        "starter_en_hello",
        "starter_en_goodbye",
        "starter_en_food_drink",
        "starter_en_feelings"
    )
    else -> null
}

internal fun starterBoardBundles(languageTag: String): List<StarterBoardBundle> {
    val danish = languageTag.startsWith("da", ignoreCase = true)
    val sharedFiles = if (danish) {
        listOf(
            "starter_da_core24",
            "starter_da_universal_core",
            "starter_da_behov",
            "starter_da_mad_og_drikke",
            "starter_da_foelelser"
        )
    } else {
        listOf(
            "starter_en_core24",
            "starter_en_universal_core",
            "starter_en_needs",
            "starter_en_food_drink",
            "starter_en_feelings"
        )
    }
    val ageGroups = if (danish) {
        listOf(
            "Børn" to "starter_da_smaa_boern",
            "Skole" to "starter_da_skole",
            "Teenagere" to "starter_da_teenagere",
            "Unge voksne" to "starter_da_unge_voksne",
            "Voksne" to "starter_da_voksne"
        )
    } else {
        listOf(
            "Children" to "starter_en_young_children",
            "School" to "starter_en_school",
            "Teenagers" to "starter_en_teenagers",
            "Young Adults" to "starter_en_young_adults",
            "Adults" to "starter_en_adults"
        )
    }
    return ageGroups.map { (name, rootFile) ->
        StarterBoardBundle(name = name, fileNames = listOf(rootFile) + sharedFiles)
    }
}

@OptIn(InternalResourceApi::class)
internal suspend fun restoreStarterBoards(
    languageTag: String,
    obfParser: ObfParser,
    boardSetUseCase: BoardSetUseCase,
    bundles: List<StarterBoardBundle> = starterBoardBundles(languageTag)
): ObfBoardSet? {
    val homeBoardId = if (languageTag.startsWith("da", ignoreCase = true)) "da_hjem" else "en_home"
    var firstCreated: ObfBoardSet? = null
    bundles.forEach { bundle ->
        val boards = buildList<ObfBoard> {
            bundle.fileNames.forEach { fileName ->
                val bytes = runCatching { Res.readBytes("files/$fileName.obf") }.getOrNull()
                    ?: return@forEach
                obfParser.parseBoard(bytes.decodeToString()).getOrNull()?.let(::add)
            }
        }
        if (boards.isEmpty()) return@forEach
        val rootBoardId = boards.first().id
        val boardIds = boards.mapTo(mutableSetOf()) { it.id }
        val selfContainedBoards = boards.map { board ->
            board.copy(
                buttons = board.buttons.map { button ->
                    val link = button.loadBoard
                    if (link?.id == homeBoardId || (link?.id != null && link.id !in boardIds)) {
                        button.copy(loadBoard = link.copy(id = rootBoardId, name = boards.first().name))
                    } else {
                        button
                    }
                }
            )
        }
        val created = boardSetUseCase.createBoardSetFromBoards(bundle.name, selfContainedBoards)
        if (firstCreated == null) firstCreated = created
    }
    return firstCreated
}
