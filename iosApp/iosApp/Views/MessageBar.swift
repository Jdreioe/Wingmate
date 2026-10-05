import SwiftUI
import Shared

/// The Message bar's speech control, shared by Typing and Screens. One large button
/// speaks, turns into Pause while speech plays and Resume while paused; Stop joins it
/// while speech is active.
struct SpeechControls: View {
    @ObservedObject var model: IosViewModel
    let buttonSize: CGFloat
    let speakSize: CGFloat
    /// Speaks the Message the way the current workspace wants (e.g. a Screen's caching).
    let onSpeak: () -> Void

    var body: some View {
        let paused = model.playback == .paused
        let playing = model.playback == .playing || model.playback == .preparing
        HStack(alignment: .bottom, spacing: 8) {
            if paused || playing {
                Button(action: model.stopSpeech) {
                    Image(systemName: "stop.fill")
                        .font(.system(size: buttonSize * 0.4, weight: .semibold))
                        .frame(width: buttonSize, height: buttonSize)
                        .background(Circle().fill(Color(.secondarySystemFill)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("playback.stop"))
                .transition(.scale.combined(with: .opacity))
            }
            Button(action: {
                if paused { model.resumeSpeech() } else if playing { model.pauseSpeech() } else { onSpeak() }
            }) {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: speakSize * 0.42, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: speakSize, height: speakSize)
                    .background(Circle().fill(Color.accentColor))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(paused ? "playback.resume" : playing ? "playback.pause" : "playback.play"))
            .accessibilityHint(Text(paused || playing ? "" : "accessibility.playback.play_hint"))
        }
        .animation(.easeInOut(duration: 0.2), value: paused || playing)
    }
}

/// The Typing Message bar: the input-surface toggle, the Message, and the speech
/// control as the largest target on the thumb side. All transport lives here.
struct TypingMessageBar: View {
    @ObservedObject var model: IosViewModel
    @Binding var messageFocused: Bool
    let fontSize: CGFloat
    let minHeight: CGFloat
    let iconSize: CGFloat

    private var buttonSize: CGFloat { min(max(iconSize * 1.55, 48), 72) }
    private var speakSize: CGFloat { min(max(iconSize * 1.75, 56), 88) }

    private var inputBinding: Binding<String> {
        Binding(get: { model.input }, set: { model.onInputChanged($0) })
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            // The tray is the resting surface; the keyboard takes its place while typing.
            Button(action: { messageFocused.toggle() }) {
                Image(systemName: messageFocused ? "plus" : "keyboard")
                    .font(.system(size: buttonSize * 0.4, weight: .semibold))
                    .frame(width: buttonSize, height: buttonSize)
                    .background(Circle().fill(Color(.secondarySystemFill)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(messageFocused ? "typing.show_screen" : "typing.show_keyboard"))
            .accessibilityHidden(model.scanningEnabled && !model.scanPlaybackAreaEnabled)

            MultiLineInput(
                text: inputBinding,
                selectedRange: Binding(get: { model.inputSelectionRange }, set: { model.inputSelectionRange = $0 }),
                isFocused: $messageFocused,
                placeholder: NSLocalizedString("tts.placeholder", comment: ""),
                fontSize: fontSize,
                minHeight: max(48, minHeight),
                maxHeight: max(160, minHeight * 2.5),
                scanEnabled: model.scanningEnabled,
                includeInScanArea: model.scanInputFieldEnabled,
                secondaryLanguage: model.secondaryLanguage,
                secondaryLanguageRanges: model.secondaryLanguageRanges,
                allowsSecondaryLanguageAction: model.hasUsableSecondaryLanguage,
                onMarkSelectionAsSecondaryLanguage: { model.markSelectionAsSecondaryLanguage(range: $0) }
            )

            SpeechControls(model: model, buttonSize: buttonSize, speakSize: speakSize) {
                model.speakMessage()
            }
            .accessibilityHidden(model.scanningEnabled && !model.scanPlaybackAreaEnabled)
        }
    }
}

/// The Held message, one line with Swap, shown above the Message bar.
struct HeldMessageRow: View {
    @ObservedObject var model: IosViewModel
    let heldText: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "bookmark.fill")
                .foregroundStyle(Color.accentColor)
                .accessibilityHidden(true)
            Text(String(format: NSLocalizedString("typing.held_message", comment: ""), heldText))
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: model.toggleHoldThatThought) {
                Label("typing.held_message.swap", systemImage: "arrow.left.arrow.right")
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            .accessibilityHint(Text("accessibility.playback.restore_thought_hint"))
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 2)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(.secondarySystemFill)))
        .accessibilityElement(children: .contain)
    }
}
