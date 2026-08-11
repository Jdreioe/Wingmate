package io.github.jdreioe.wingmate.domain.obf

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.TtsEngine
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #119 — immediate versus composed-sentence speech.
 *
 * The single shared gate [shouldSpeakSelectionImmediately] decides both text
 * and recorded-sound playback on every native client, so the matrix below is
 * the source of truth for phrases, OBF buttons, and recordings.
 */
class SpeechPolicyTest {

    @Test
    fun immediatePolicySpeaksSpeakableBoardBehaviors() {
        assertTrue(
            shouldSpeakSelectionImmediately(SpeechPolicy.Immediate, BoardActivationBehavior.SpeakAndAdd)
        )
        assertTrue(
            shouldSpeakSelectionImmediately(SpeechPolicy.Immediate, BoardActivationBehavior.SpeakOnly)
        )
        // A board that opts out of speech stays silent even in immediate mode.
        assertFalse(
            shouldSpeakSelectionImmediately(SpeechPolicy.Immediate, BoardActivationBehavior.AddOnly)
        )
    }

    @Test
    fun sentenceOnlyPolicyNeverSpeaksDuringComposition() {
        BoardActivationBehavior.entries.forEach { behavior ->
            assertFalse(
                shouldSpeakSelectionImmediately(SpeechPolicy.SentenceOnly, behavior),
                "SentenceOnly must stay silent for $behavior"
            )
        }
    }

    @Test
    fun phraseSelectionsFollowTheSamePolicy() {
        assertTrue(shouldSpeakPhraseSelection(SpeechPolicy.Immediate))
        assertFalse(shouldSpeakPhraseSelection(SpeechPolicy.SentenceOnly))
    }

    @Test
    fun compositionStillAddsSelectionsInEveryMode() {
        // Sentence construction stays correct regardless of the speech policy.
        assertEquals(
            true,
            shouldAddBoardSelection(BoardActivationBehavior.SpeakAndAdd)
        )
        assertTrue(shouldAddBoardSelection(BoardActivationBehavior.AddOnly))
        assertFalse(shouldAddBoardSelection(BoardActivationBehavior.SpeakOnly))
    }

    @Test
    fun nonSpeechActionsNeverProduceASpeakEffect() {
        listOf(":space", ":backspace", ":clear", ":home", ":native-keyboard", ":prediction")
            .forEach { action ->
                assertFalse(
                    parseObfButtonAction(action) === ObfButtonActionEffect.Speak,
                    "$action must never produce a Speak effect"
                )
            }
    }

    @Test
    fun speakActionAlwaysSpeaksTheSentence() {
        // Explicit sentence activation is the one speech path that ignores the policy.
        assertTrue(parseObfButtonAction(":speak") === ObfButtonActionEffect.Speak)
    }

    @Test
    fun defaultPolicyIsImmediateAndRoundTripsThroughSettingsJson() {
        assertEquals(SpeechPolicy.Immediate, Settings().speechPolicy)

        val json = Json { ignoreUnknownKeys = true }
        val settings = Settings(
            ttsEngine = TtsEngine.SYSTEM,
            speechPolicy = SpeechPolicy.SentenceOnly
        )
        val roundTripped = json.decodeFromString<Settings>(
            json.encodeToString(Settings.serializer(), settings)
        )
        assertEquals(SpeechPolicy.SentenceOnly, roundTripped.speechPolicy)
        // An existing saved setting without the new field keeps the immediate default.
        assertEquals(
            SpeechPolicy.Immediate,
            json.decodeFromString<Settings>("{}").speechPolicy
        )
    }
}