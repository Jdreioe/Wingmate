package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard

/**
 * A ready-to-use, editable keyboard with lowercase, uppercase, and symbols pages.
 *
 * Every page ends with a prediction row: four n-gram prediction buttons (each
 * two cells wide), a "delete sentence" button, and a "say" button. The boards
 * are marked as spelling boards so word buttons do not auto-insert spaces.
 */
object KeyboardBoardTemplate {
    private const val LOWERCASE = "keyboard-lowercase"
    private const val UPPERCASE = "keyboard-uppercase"
    private const val SYMBOLS = "keyboard-symbols"
    private const val COLUMN_COUNT = 11

    fun boards(): List<ObfBoard> = listOf(
        keyboardBoard(LOWERCASE, "Keyboard", lowercaseRows()),
        keyboardBoard(UPPERCASE, "Keyboard — uppercase", uppercaseRows()),
        keyboardBoard(SYMBOLS, "Numbers & symbols", symbolRows())
    )

    private fun keyboardBoard(id: String, name: String, rows: List<List<Key?>>): ObfBoard {
        val buttonKeys = rows.flatten().filterNotNull().distinct()
        val buttonIds = buttonKeys.mapIndexed { index, key -> key to "$id-key-$index" }.toMap()
        return ObfBoard(
            format = "open-board-0.1",
            id = id,
            name = name,
            buttons = buttonKeys.mapIndexed { index, key ->
                ObfButton(
                    id = "$id-key-$index",
                    label = key.label,
                    vocalization = key.vocalization,
                    action = key.action,
                    loadBoard = key.loadBoard?.let { target -> ObfLoadBoard(id = target) }
                ).withType(key.type)
            },
            grid = ObfGrid(
                rows = rows.size,
                columns = COLUMN_COUNT,
                order = rows.map { row -> row.map { key -> key?.let(buttonIds::getValue) } }
            )
        ).withCompactGrid(true).withSpellingMode(true)
    }

    private fun lowercaseRows(): List<List<Key?>> =
        letterRows("qwertyuiop", "asdfghjkl", "zxcvbnm") +
            listOf(controls(shiftKey("⇧", UPPERCASE), numberKey()), predictionsRow())

    private fun uppercaseRows(): List<List<Key?>> =
        letterRows("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM") +
            listOf(controls(shiftKey("⇩", LOWERCASE), numberKey()), predictionsRow())

    private fun symbolRows(): List<List<Key?>> = listOf(
        characters("1234567890") + key("⌫", "backspace", ":backspace"),
        characters("-/:;()$&@_") + key("\"", "double quote", "+\""),
        characters("#+=.,?!'[]") + spaceKey(),
        controls(shiftKey("ABC", LOWERCASE), shiftKey("⇧", UPPERCASE)),
        predictionsRow()
    )

    private fun letterRows(first: String, second: String, third: String): List<List<Key?>> = listOf(
        characters(first) + key("⌫", "backspace", ":backspace"),
        listOf(null) + characters(second) + key("?", "question mark", "+?"),
        listOf(null, null) + characters(third) + listOf(key(",", "comma", "+,"), key(".", "period", "+."))
    )

    /** Shift + number row. Space keys are the centre of the bar. */
    private fun controls(left: Key, right: Key): List<Key?> =
        listOf(left) + List(9) { spaceKey() } + listOf(right)

    private fun predictionsRow(): List<Key?> {
        fun predict(index: Int): Key =
            key(id = "predict$index", label = "Predict", vocalization = "predict", action = ":prediction")
        val delete = key(id = "delete-sentence", label = "Delete", vocalization = "delete sentence", action = ":clear")
        val say = key(id = "say", label = "Say", vocalization = "speak", action = ":speak")
        return listOf(
            predict(1), predict(1),
            predict(2), predict(2),
            predict(3), predict(3),
            predict(4), predict(4),
            delete,
            say, say
        )
    }

    private fun numberKey(): Key = shiftKey("123", SYMBOLS)
    private fun shiftKey(label: String, target: String): Key = Key(id = "shift-$label", label = label, vocalization = label, loadBoard = target)
    private fun characters(value: String): List<Key?> = value.map(::character)
    private fun character(value: Char): Key = key(value.toString(), value.toString(), "+$value")
    private fun spaceKey(): Key = key("Space", "space", ":space")
    private fun key(
        label: String,
        vocalization: String,
        action: String = "",
        type: ObfButtonType = ObfButtonType.Standard,
        id: String = ""
    ): Key = Key(id, label, vocalization, action, type)

    private data class Key(
        val id: String = "",
        val label: String,
        val vocalization: String,
        val action: String = "",
        val type: ObfButtonType = ObfButtonType.Standard,
        val loadBoard: String? = null
    )
}