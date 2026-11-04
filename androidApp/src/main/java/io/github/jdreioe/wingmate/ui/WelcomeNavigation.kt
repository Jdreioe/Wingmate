package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.saveable.listSaver

/** One page of setup. [analyticsName] is stable across R8 renaming. */
internal enum class WelcomeStep(val analyticsName: String) {
    Start("start"),
    VoiceEngine("voice_engine"),
    AzureSetup("azure_setup"),
    GoogleSetup("google_setup"),
    ElevenLabsSetup("elevenlabs_setup"),
    Voice("voice"),
    Language("language"),
    TestVoice("test_voice"),
    Workspace("workspace"),
    Privacy("privacy"),
    Review("review"),
}

/**
 * Setup's page stack. Every move names the page it starts from and does nothing when that
 * page is no longer shown, so a repeated activation (dwell, tremor, double tap) moves once.
 * A page opened with Change from Review returns to Review on Back and when finished.
 */
internal data class WelcomeNavigation(
    val path: List<WelcomeStep> = listOf(WelcomeStep.Start),
    /** The path to Review while a page opened with Change is shown; empty otherwise. */
    val reviewPath: List<WelcomeStep> = emptyList(),
) {
    val step: WelcomeStep get() = path.last()
    val canGoBack: Boolean get() = path.size > 1

    fun advance(from: WelcomeStep, to: WelcomeStep): WelcomeNavigation =
        if (step != from) this else copy(path = path + to)

    fun back(from: WelcomeStep): WelcomeNavigation {
        if (step != from || !canGoBack) return this
        val previous = path.dropLast(1)
        return copy(path = previous, reviewPath = if (previous.last() == WelcomeStep.Review) emptyList() else reviewPath)
    }

    fun change(target: WelcomeStep): WelcomeNavigation =
        if (step != WelcomeStep.Review) this else copy(path = path + target, reviewPath = path)

    /** Moves on from the last page of a section, or back to Review after a Change. */
    fun finishSection(from: WelcomeStep, next: WelcomeStep): WelcomeNavigation = when {
        step != from -> this
        reviewPath.isNotEmpty() -> WelcomeNavigation(path = reviewPath)
        else -> copy(path = path + next)
    }

    companion object {
        /** Keeps the navigation across activity recreation. */
        val Saver = listSaver<WelcomeNavigation, List<String>>(
            save = { listOf(it.path.map(WelcomeStep::name), it.reviewPath.map(WelcomeStep::name)) },
            restore = { (path, review) ->
                WelcomeNavigation(path.map(WelcomeStep::valueOf), review.map(WelcomeStep::valueOf))
            },
        )
    }
}
