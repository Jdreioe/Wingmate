package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfKeyboardLayout
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard

/**
 * Authorable keyboard presets. Each preset produces lowercase, uppercase, and
 * symbols pages whose letter pages are tagged with the matching layout so custom
 * keyboards can be created, duplicated, and round-tripped with a stable identity.
 */
enum class KeyboardPreset { Qwerty, Alphabetical }

/**
 * A ready-to-use, editable keyboard with lowercase, uppercase, and symbols pages.
 *
 * Every page starts with a prediction row above the keys: four n-gram prediction
 * buttons (each two cells wide), a "delete sentence" button, and a "say" button.
 * The boards are marked as spelling boards so word buttons do not auto-insert spaces.
 */
object KeyboardBoardTemplate {
    private const val LOWERCASE = "keyboard-lowercase"
    private const val UPPERCASE = "keyboard-uppercase"
    private const val SYMBOLS = "keyboard-symbols"
    private const val COLUMN_COUNT = 11

    fun boards(preset: KeyboardPreset = KeyboardPreset.Qwerty): List<ObfBoard> {
        val (lower, upper, layout) = letters(preset)
        return listOf(
            keyboardBoard(LOWERCASE, "Keyboard", lowercaseRows(lower), layout),
            keyboardBoard(UPPERCASE, "Keyboard — uppercase", uppercaseRows(upper), layout),
            keyboardBoard(SYMBOLS, "Numbers & symbols", symbolRows(), ObfKeyboardLayout.Symbols)
        )
    }

    private fun letters(
        preset: KeyboardPreset
    ): Triple<Triple<String, String, String>, Triple<String, String, String>, ObfKeyboardLayout> = when (preset) {
        KeyboardPreset.Qwerty -> Triple(
            Triple("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            Triple("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"),
            ObfKeyboardLayout.Qwerty
        )
        KeyboardPreset.Alphabetical -> Triple(
            Triple("abcdefghij", "klmnopqrs", "tuvwxyz"),
            Triple("ABCDEFGHIJ", "KLMNOPQRS", "TUVWXYZ"),
            ObfKeyboardLayout.Alphabetical
        )
    }

    private fun keyboardBoard(
        id: String,
        name: String,
        rows: List<List<Key?>>,
        layout: ObfKeyboardLayout
    ): ObfBoard {
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
        ).withCompactGrid(true).withSpellingMode(true).withKeyboardLayout(layout)
    }

    private fun lowercaseRows(letters: Triple<String, String, String>): List<List<Key?>> =
        listOf(predictionsRow()) + letterRows(letters.first, letters.second, letters.third) +
            listOf(controls(shiftKey("⇧", UPPERCASE), numberKey()))

    private fun uppercaseRows(letters: Triple<String, String, String>): List<List<Key?>> =
        listOf(predictionsRow()) + letterRows(letters.first, letters.second, letters.third) +
            listOf(controls(shiftKey("⇩", LOWERCASE), numberKey()))

    private fun symbolRows(): List<List<Key?>> = listOf(
        predictionsRow(),
        characters("1234567890") + key("⌫", "backspace", ":backspace"),
        characters("-/:;()$&@_") + key("\"", "double quote", "+\""),
        characters("#+=.,?!'[]") + key("~", "tilde", "+~"),
        controls(shiftKey("ABC", LOWERCASE), shiftKey("⇧", UPPERCASE))
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