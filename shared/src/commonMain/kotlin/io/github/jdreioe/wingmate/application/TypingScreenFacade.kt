package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.PageElementContent
import io.github.jdreioe.wingmate.domain.obf.pageElements
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions

enum class TypingTrayElementKind { PageNavigation, PhraseCollection, ActionStrip }

/** What one Action-strip effect does. Text effects carry their text in [TypingEffect]. */
enum class TypingEffectKind {
    InsertText,
    WrapSelection,
    Backspace,
    Clear,
    Speak,
    Pause,
    Resume,
    Stop,
    SecondaryLanguage,
    HoldMessage,
    Keyboard,

    /** Valid but meaningless in the tray (Home, Predictions); the Button stays enabled. */
    Ignored,

    /** Written by a newer client; the Button is shown but disabled. */
    Unsupported,
}

/**
 * One effect of an Action-strip Button. [InsertText][TypingEffectKind.InsertText]
 * inserts [prefix]; [WrapSelection][TypingEffectKind.WrapSelection] wraps the
 * selection in [prefix] and [suffix].
 */
data class TypingEffect(
    val kind: TypingEffectKind,
    val prefix: String,
    val suffix: String,
)

data class TypingTrayAction(
    val id: String,
    val label: String,
    val effects: List<TypingEffect>,
)

/** A Page element of the Typing Screen, placed on the template's grid. */
data class TypingTrayElement(
    val kind: TypingTrayElementKind,
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    /** Page navigation only: whether it ends with an add-Category chip. */
    val showAddCategory: Boolean,
    /** Action strips only: their Buttons in template order. */
    val actions: List<TypingTrayAction>,
)

/** The Typing Screen template, flattened for SwiftUI. */
data class TypingTray(
    val elements: List<TypingTrayElement>,
    val gridColumns: Int,
    val phraseColumns: Int,
    val activationBehavior: BoardActivationBehavior,
)

/**
 * Swift's boundary to the Typing Screen: the persistent template that lays out
 * the Typing tray (#311). iOS renders Phrases from its Phrase store and takes
 * the tray's layout, Phrase columns, Action strip, and activation behavior from
 * here, so a template customized on Android looks and acts the same on iOS.
 */
class TypingScreenFacade(
    private val typingScreens: TypingScreenUseCase,
    private val settings: SettingsStateManager,
) {
    /** Loads the Typing Screen, creating the default template on first use. */
    suspend fun tray(): TypingTray {
        val gridColumns = settings.getCurrentSettings().gridColumns
        val graph = typingScreens.getOrCreate(gridColumns)
        val template = requireNotNull(graph.rootBoard) { "The Typing Screen has no template Page" }
        val buttonsById = template.buttons.associateBy(ObfButton::id)
        val elements = template.pageElements()
            .sortedWith(compareBy({ it.row }, { it.column }))
            .mapNotNull { element ->
                val content = element.content
                val kind = when (content) {
                    is PageElementContent.PageNavigation -> TypingTrayElementKind.PageNavigation
                    is PageElementContent.PhraseCollection -> TypingTrayElementKind.PhraseCollection
                    is PageElementContent.ActionStrip -> TypingTrayElementKind.ActionStrip
                    is PageElementContent.Unsupported -> return@mapNotNull null
                }
                TypingTrayElement(
                    kind = kind,
                    row = element.row,
                    column = element.column,
                    rowSpan = element.rowSpan.coerceAtLeast(1),
                    columnSpan = element.columnSpan.coerceAtLeast(1),
                    showAddCategory = (content as? PageElementContent.PageNavigation)
                        ?.configuration?.showAddCategory ?: false,
                    actions = (content as? PageElementContent.ActionStrip)
                        ?.configuration?.buttonIds.orEmpty()
                        .mapNotNull(buttonsById::get)
                        .map { it.toTrayAction() },
                )
            }
        val phraseColumns = template.pageElements()
            .map { it.content }
            .filterIsInstance<PageElementContent.PhraseCollection>()
            .firstOrNull()?.configuration?.columns
            ?: gridColumns
        return TypingTray(
            elements = elements,
            gridColumns = (template.grid?.columns ?: phraseColumns).coerceIn(1, 12),
            phraseColumns = phraseColumns.coerceIn(1, 12),
            activationBehavior = graph.boardSet.screenSettings.activationBehavior
                ?: BoardActivationBehavior.SpeakOnly,
        )
    }
}

private fun ObfButton.toTrayAction() = TypingTrayAction(
    id = id,
    label = label.orEmpty(),
    effects = parseObfButtonActions(this).map { effect ->
        when (effect) {
            is ObfButtonActionEffect.AppendText -> TypingEffect(TypingEffectKind.InsertText, effect.text, "")
            is ObfButtonActionEffect.WrapSelection -> TypingEffect(TypingEffectKind.WrapSelection, effect.prefix, effect.suffix)
            ObfButtonActionEffect.Backspace -> TypingEffect(TypingEffectKind.Backspace, "", "")
            ObfButtonActionEffect.Clear -> TypingEffect(TypingEffectKind.Clear, "", "")
            ObfButtonActionEffect.Speak -> TypingEffect(TypingEffectKind.Speak, "", "")
            ObfButtonActionEffect.Pause -> TypingEffect(TypingEffectKind.Pause, "", "")
            ObfButtonActionEffect.Resume -> TypingEffect(TypingEffectKind.Resume, "", "")
            ObfButtonActionEffect.Stop -> TypingEffect(TypingEffectKind.Stop, "", "")
            ObfButtonActionEffect.ToggleSecondaryLanguage -> TypingEffect(TypingEffectKind.SecondaryLanguage, "", "")
            ObfButtonActionEffect.SwapHeldMessage -> TypingEffect(TypingEffectKind.HoldMessage, "", "")
            ObfButtonActionEffect.NativeKeyboard -> TypingEffect(TypingEffectKind.Keyboard, "", "")
            ObfButtonActionEffect.Home, ObfButtonActionEffect.Predictions -> TypingEffect(TypingEffectKind.Ignored, "", "")
            is ObfButtonActionEffect.Unsupported -> TypingEffect(TypingEffectKind.Unsupported, "", "")
        }
    },
)
