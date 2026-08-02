package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard

/** A ready-to-use, editable keyboard with lowercase, uppercase, and symbols pages. */
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
        ).withCompactGrid(true)
    }

    private fun lowercaseRows(): List<List<Key?>> = letterRows("qwertyuiop", "asdfghjkl", "zxcvbnm") +
        listOf(controls(shiftKey("⇧", UPPERCASE), numberKey()))

    private fun uppercaseRows(): List<List<Key?>> = letterRows("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM") +
        listOf(controls(shiftKey("⇩", LOWERCASE), numberKey()))

    private fun symbolRows(): List<List<Key?>> = listOf(
        characters("1234567890") + key("⌫", "backspace", ":backspace"),
        characters("-/:;()$&@_") + key("\"", "double quote", "+\""),
        characters("#+=.,?!'[]") + key("Clear", "clear", ":clear"),
        controls(shiftKey("ABC", LOWERCASE), shiftKey("⇧", UPPERCASE))
    )

    private fun letterRows(first: String, second: String, third: String): List<List<Key?>> = listOf(
        characters(first) + key("⌫", "backspace", ":backspace"),
        listOf(null) + characters(second) + key("?", "question mark", "+?"),
        listOf(null, null) + characters(third) + listOf(key(",", "comma", "+,"), key(".", "period", "+."))
    )

    private fun controls(left: Key, alternate: Key): List<Key?> {
        val space = key("Space", "space", ":space")
        return listOf(left, space, space, space, space, space, space, space,
            key("Next", "predict", type = ObfButtonType.NGramPrediction), alternate,
            key("Say", "speak", ":speak"))
    }

    private fun numberKey(): Key = shiftKey("123", SYMBOLS)
    private fun shiftKey(label: String, target: String): Key = Key(label, label, loadBoard = target)
    private fun characters(value: String): List<Key?> = value.map(::character)
    private fun character(value: Char): Key = key(value.toString(), value.toString(), "+$value")
    private fun key(
        label: String,
        vocalization: String,
        action: String = "",
        type: ObfButtonType = ObfButtonType.Standard
    ): Key = Key(label, vocalization, action, type)

    private data class Key(
        val label: String,
        val vocalization: String,
        val action: String = "",
        val type: ObfButtonType = ObfButtonType.Standard,
        val loadBoard: String? = null
    )
}
