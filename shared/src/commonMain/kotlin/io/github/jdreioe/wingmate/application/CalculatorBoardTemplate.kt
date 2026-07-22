package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard

/** Three-page calculator supplied as an editable Open Board Format screen. */
object CalculatorBoardTemplate {
    private const val REGULAR = "calculator-regular"
    private const val SCIENTIFIC = "calculator-scientific"
    private const val ENGINEERING = "calculator-engineering"

    fun boards(): List<ObfBoard> = listOf(
        board(
            id = REGULAR,
            name = "Regular",
            rows = listOf(
                keys("C" to ":clear", "⌫" to ":backspace", "(" to "+(", ")" to "+)", "%" to "+%", "=" to "+="),
                keys("7", "8", "9", "÷", "√(" to "+√(", "Speak" to ":speak"),
                keys("4", "5", "6", "×", "²", "π"),
                keys("1", "2", "3", "−", "+", "."),
                keys("0", "00", "e", "E", "^", "±" to "+−")
            )
        ),
        board(
            id = SCIENTIFIC,
            name = "Scientific",
            rows = listOf(
                keys("sin(" to "+sin(", "cos(" to "+cos(", "tan(" to "+tan(", "log(" to "+log(", "ln(" to "+ln(", "√(" to "+√("),
                keys("asin(" to "+asin(", "acos(" to "+acos(", "atan(" to "+atan(", "(" to "+(", ")" to "+)", "=" to "+="),
                keys("7", "8", "9", "÷", "²", "π"),
                keys("4", "5", "6", "×", "^", "e"),
                keys("1", "2", "3", "−", "+", "."),
                keys("0", "C" to ":clear", "⌫" to ":backspace", "%", "±" to "+−", "Speak" to ":speak")
            )
        ),
        board(
            id = ENGINEERING,
            name = "Engineering",
            rows = listOf(
                keys("EE" to "+E", "10^(" to "+10^(", "√(" to "+√(", "²", "^", "=" to "+="),
                keys("π", "e", "(", ")", "%", "Speak" to ":speak"),
                keys("7", "8", "9", "÷", "sin(" to "+sin(", "cos(" to "+cos("),
                keys("4", "5", "6", "×", "log(" to "+log(", "ln(" to "+ln("),
                keys("1", "2", "3", "−", "+", "."),
                keys("0", "00", "C" to ":clear", "⌫" to ":backspace", "±" to "+−", "E−" to "+E−")
            )
        )
    )

    private fun board(id: String, name: String, rows: List<List<Key>>): ObfBoard {
        val modeButtons = listOf(
            modeButton(id, REGULAR, "Regular"),
            modeButton(id, SCIENTIFIC, "Scientific"),
            modeButton(id, ENGINEERING, "Engineering")
        )
        val keyButtons = rows.flatten().mapIndexed { index, key ->
            ObfButton(
                id = "$id-key-$index",
                label = key.label,
                vocalization = key.label,
                action = key.action ?: "+${key.label}"
            ).withMathMode(key.action !in setOf(":clear", ":backspace", ":speak"))
        }
        val modeRow = modeButtons.flatMap { listOf(it.id, it.id) }
        val keyRows = rows.indices.map { row ->
            List(6) { column -> "$id-key-${row * 6 + column}" }
        }
        return ObfBoard(
            format = "open-board-0.1",
            id = id,
            name = name,
            buttons = modeButtons + keyButtons,
            grid = ObfGrid(rows = keyRows.size + 1, columns = 6, order = listOf(modeRow) + keyRows)
        )
    }

    private fun modeButton(currentId: String, targetId: String, label: String): ObfButton = ObfButton(
        id = "$currentId-mode-$targetId",
        label = label,
        loadBoard = ObfLoadBoard(id = targetId, name = label),
        backgroundColor = if (currentId == targetId) "#6750A4" else "#E8DEF8"
    )

    private data class Key(val label: String, val action: String? = null)
    private fun keys(vararg values: Any): List<Key> =
        values.map {
            when (it) {
                is Pair<*, *> -> Key(it.first as String, it.second as String)
                else -> Key(it as String)
            }
        }
}
