package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.NativeSubscription
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationFailureKind
import io.github.jdreioe.wingmate.domain.CommunicationPersistenceStatus
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.CommunicationSessionState
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.fromTextDiff
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakSelectionImmediately
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where speech stands. Flattened for Swift, which cannot see the session's own types. */
enum class SessionPlayback { Idle, Preparing, Playing, Paused }

/** The notice the session currently needs to show. */
enum class SessionNotice {
    /** The Message could not be loaded or saved; offer Retry. */
    StorageFailed,

    /** Speech could not finish; the Message is kept. */
    PlaybackFailed,

    /** The cloud voice failed, so the device voice spoke instead. Informational. */
    SpeechFallback,
}

/** Where the cursor goes after a Phrase activation, and whether to speak the Phrase. */
data class NativePhraseActivation(
    val cursor: Int,
    val shouldSpeak: Boolean,
)

/** Swift's read-only view of the Communication session. */
data class NativeCommunicationState(
    val activeMessage: Message,
    val heldMessage: Message?,
    val playback: SessionPlayback,
    val notice: SessionNotice?,
    /** True while the Message is being written, so a storage Retry can be disabled. */
    val isSaving: Boolean,
    /**
     * False until the saved Message has loaded. Edits before then would replace the
     * saved active and held Messages, so the UI holds them back.
     */
    val isLoaded: Boolean,
)

/**
 * Swift's boundary to the shared Communication session (#306). iOS composes,
 * holds, and speaks the one Message here, so engine choice, device fallback,
 * and History stay in Kotlin. Offsets are UTF-16 code units, which match
 * NSString and NSRange.
 */
class CommunicationSessionFacade(
    private val session: CommunicationSession,
    private val settings: SettingsStateManager,
) {
    fun state(): NativeCommunicationState = session.state.value.toNative()

    /** Calls [onChange] on the main thread with the current state and then each change. */
    fun observe(onChange: (NativeCommunicationState) -> Unit): NativeSubscription {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            session.state
                .map { it.toNative() }
                .distinctUntilChanged()
                .collect { onChange(it) }
        }
        return NativeSubscription(scope)
    }

    /** Applies a text field's full new text as one edit that keeps untouched Message parts. */
    fun editText(newText: String) {
        session.accept(Message.fromTextDiff(currentText(), newText))
    }

    fun replaceRange(start: Int, endExclusive: Int, replacement: String) {
        session.accept(CommunicationAction.ReplaceRange(TextSpan(start, endExclusive), replacement))
    }

    /** Marks the range as the secondary language, or unmarks it if it already is. */
    fun toggleSecondaryLanguage(start: Int, endExclusive: Int) {
        val languageTag = settings.getCurrentSettings().secondaryLanguage
        if (languageTag.isBlank()) return
        session.accept(CommunicationAction.ToggleLanguage(TextSpan(start, endExclusive), languageTag))
    }

    /**
     * Activates a Typing Screen Phrase per the Screen's activation behavior and the
     * speech policy. Adding replaces the selection [start, endExclusive) with it as one
     * Phrase part, so its recording plays when the Message is spoken, with a space on
     * either side where needed. Speaking is left to the caller, which knows the voice.
     * Until the Typing Screen has loaded, [activationBehavior] is null and the Phrase
     * is only spoken, the Typing Screen's default.
     */
    fun activatePhrase(
        phrase: Phrase,
        start: Int,
        endExclusive: Int,
        activationBehavior: BoardActivationBehavior?,
    ): NativePhraseActivation {
        val behavior = activationBehavior ?: BoardActivationBehavior.SpeakOnly
        val shouldSpeak = shouldSpeakSelectionImmediately(settings.getCurrentSettings().speechPolicy, behavior)
        if (!shouldAddBoardSelection(behavior)) return NativePhraseActivation(start, shouldSpeak)
        val message = session.state.value.activeMessage
        val text = message.displayText
        val at = start.coerceIn(0, text.length)
        val end = endExclusive.coerceIn(at, text.length)
        val before = if (at > 0 && !text[at - 1].isWhitespace()) " " else ""
        val after = if (end == text.length || !text[end].isWhitespace()) " " else ""
        val spoken = phrase.name?.ifBlank { null } ?: phrase.text
        val part = MessagePart(
            displayText = before + phrase.text + after,
            spokenText = before + spoken + after,
            source = MessagePartSource.Phrase(phrase.id),
            recordingPath = phrase.recordingPath,
        )
        session.accept(CommunicationAction.ReplaceMessage(message.replaceRange(at, end, part)))
        return NativePhraseActivation(at + part.displayText.length, shouldSpeak)
    }

    /** Appends a Screen Button's text, separated by a space unless the Page spells. */
    fun appendScreenPart(
        text: String,
        screenId: String,
        pageId: String,
        buttonId: String,
        spellingMode: Boolean,
    ) {
        session.accept(
            CommunicationAction.AppendPart(
                part = MessagePart(
                    displayText = text,
                    source = MessagePartSource.ScreenButton(screenId, pageId, buttonId),
                ),
                spellingMode = spellingMode,
            )
        )
    }

    fun removeLastPart(spellingMode: Boolean) {
        session.accept(CommunicationAction.RemoveLastPart(spellingMode))
    }

    fun clear() = session.accept(CommunicationAction.Clear)

    /** Holds the active Message, or swaps it with the held one. */
    fun swapHeldMessage() = session.accept(CommunicationAction.SwapHeldMessage)

    /** Speaks the whole Message and records it in History once it has been spoken. */
    fun speak(voice: Voice?, cacheAudio: Boolean) {
        session.accept(CommunicationAction.SpeakActive(voice, cacheAudio))
    }

    /** Speaks [text] on its own (previews, single Buttons) without the Message or History. */
    fun speakText(text: String, voice: Voice?) {
        if (text.isBlank()) return
        session.accept(CommunicationAction.SpeakPart(MessagePart(text), voice))
    }

    /** Speaks a Phrase on its own, playing its recording when it has one. */
    fun speakPhrase(phrase: Phrase, voice: Voice?) {
        session.accept(
            CommunicationAction.SpeakPart(
                part = MessagePart(
                    displayText = phrase.text,
                    spokenText = phrase.name?.ifBlank { null } ?: phrase.text,
                    source = MessagePartSource.Phrase(phrase.id),
                    recordingPath = phrase.recordingPath,
                ),
                voice = voice,
            )
        )
    }

    /** Plays a recording on its own (a Button's sound), so Pause and Stop reach it too. */
    fun speakRecording(path: String, voice: Voice?) {
        session.accept(
            CommunicationAction.SpeakPart(MessagePart(displayText = "", recordingPath = path), voice)
        )
    }

    fun pause() = session.accept(CommunicationAction.Pause)
    fun resume() = session.accept(CommunicationAction.Resume)
    fun stop() = session.accept(CommunicationAction.Stop)
    fun dismissNotice() = session.accept(CommunicationAction.DismissFailure)
    fun retryStorage() = session.accept(CommunicationAction.RetryPersistence)

    /** Reloads the Message after a backup restore replaced it on disk. */
    suspend fun reloadAfterRestore() = session.reloadAfterRestore()

    private fun currentText(): String = session.state.value.activeMessage.displayText
}

private fun CommunicationSessionState.toNative(): NativeCommunicationState {
    val storageFailed = persistenceStatus == CommunicationPersistenceStatus.Failed ||
        lastFailure?.kind == CommunicationFailureKind.Persistence
    return NativeCommunicationState(
        activeMessage = activeMessage,
        heldMessage = heldMessage,
        playback = when (playbackStatus) {
            CommunicationPlaybackStatus.Idle -> SessionPlayback.Idle
            CommunicationPlaybackStatus.Preparing -> SessionPlayback.Preparing
            CommunicationPlaybackStatus.Playing -> SessionPlayback.Playing
            CommunicationPlaybackStatus.Paused -> SessionPlayback.Paused
        },
        notice = when {
            storageFailed -> SessionNotice.StorageFailed
            lastFailure?.kind == CommunicationFailureKind.Playback -> SessionNotice.PlaybackFailed
            lastFailure?.kind == CommunicationFailureKind.SpeechFallback -> SessionNotice.SpeechFallback
            else -> null
        },
        isSaving = persistenceStatus == CommunicationPersistenceStatus.Saving,
        isLoaded = isInitialized,
    )
}
