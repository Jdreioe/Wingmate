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
        let paused = model.playback.name == "Paused"
        let playing = model.playback.name == "Playing" || model.playback.name == "Preparing"
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
        .accessibilityHidden(model.scanningEnabled && !model.scanPlaybackAreaEnabled)
    }
}

/// The Screens message bar: the Message's Buttons as symbol chips in the same
/// rounded field as Typing, followed by the same speech control.
struct ScreensMessageBar: View {
    @ObservedObject var model: IosViewModel
    let tokens: [SentencePhraseToken]
    let showSpeakControl: Bool
    let onDelete: (Int) -> Void
    let onSpeak: () -> Void
    var animationNamespace: Namespace.ID? = nil
    var animatedTokenId: String? = nil
    var iconSize: CGFloat = 36

    private var buttonSize: CGFloat { min(max(iconSize * 1.55, 48), 72) }
    private var speakSize: CGFloat { min(max(iconSize * 1.75, 56), 88) }

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ScrollViewReader { reader in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(tokens.enumerated()), id: \.element.id) { index, token in
                            tokenChip(token, index: index)
                                .id(token.id)
                        }
                    }
                    .padding(8)
                    .animation(.spring(response: 0.38, dampingFraction: 0.82), value: tokens.map(\.id))
                }
                .onChange(of: tokens.count) { _, _ in
                    // Keep the newest Button in view.
                    if let last = tokens.last { withAnimation { reader.scrollTo(last.id, anchor: .trailing) } }
                }
            }
            .frame(maxWidth: .infinity, minHeight: speakSize, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(.secondarySystemFill)))
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("screens.message_bar"))

            if showSpeakControl {
                SpeechControls(model: model, buttonSize: buttonSize, speakSize: speakSize, onSpeak: onSpeak)
            }
        }
    }

    @ViewBuilder
    private func tokenChip(_ token: SentencePhraseToken, index: Int) -> some View {
        let chip = HStack(spacing: 6) {
            if let imageUrl = token.imageUrl, let url = URL(string: imageUrl) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFit()
                } placeholder: {
                    Color.clear
                }
                .frame(width: 32, height: 32)
                .accessibilityHidden(true)
            }
            Text(token.title.isEmpty ? " " : token.title)
                .lineLimit(1)
            Button { onDelete(index) } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("sentence.box.delete_phrase"))
        }
        .padding(.leading, 10)
        .padding(.trailing, 2)
        .frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(.tertiarySystemBackground)))

        if let animationNamespace, animatedTokenId == token.id {
            chip
                .matchedGeometryEffect(id: token.id, in: animationNamespace, isSource: false)
                .zIndex(2)
        } else {
            chip.transition(.move(edge: .trailing).combined(with: .opacity))
        }
    }
}
