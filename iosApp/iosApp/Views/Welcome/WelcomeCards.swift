import SwiftUI
import Shared
import AVFoundation

struct TtsOptionCard: View {
    let title: String
    let subtitle: String
    let icon: String
    let pros: [String]
    let cons: [String]
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: icon)
                        .font(.title2)
                        .foregroundColor(.blue)
                        .frame(width: 28, alignment: .center)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.headline)
                            .foregroundColor(.primary)
                            .multilineTextAlignment(.leading)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(isSelected ? .blue : .secondary)
                }
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(pros, id: \.self) { item in
                        Label(item, systemImage: "checkmark")
                            .font(.caption)
                            .foregroundColor(.primary)
                            .multilineTextAlignment(.leading)
                    }
                    ForEach(cons, id: \.self) { item in
                        Label(item, systemImage: "minus")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.leading)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? Color.blue : Color(.separator).opacity(0.6), lineWidth: isSelected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }
}

struct SelectedVoiceCard: View {
    let voice: Shared.Voice
    let onSpeak: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(voice.displayName ?? voice.name ?? NSLocalizedString("welcome_flow.choose_voice", comment: ""))
                    .font(.headline)
                Text(voice.selectedLanguage ?? voice.primaryLanguage ?? "")
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Button(action: onSpeak) { Image(systemName: "speaker.wave.2.fill") }
                .buttonStyle(.bordered)
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

/// The on-device voice Wingmate uses for the primary language, or "System default" when the
/// user has not picked one in Wingmate's system voice picker.
struct SelectedSystemVoiceCard: View {
    let voice: AVSpeechSynthesisVoice?
    let language: String
    let onSpeak: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(voice?.name ?? NSLocalizedString("voice.system.default", comment: ""))
                    .font(.headline)
                Text(voice?.language ?? language)
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Button(action: onSpeak) { Image(systemName: "speaker.wave.2.fill") }
                .buttonStyle(.bordered)
                .accessibilityLabel(Text("voice.system.preview"))
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

struct VoicePreviewCard: View {
    let title: String
    @Binding var previewText: String
    let onSpeak: () -> Void
    let onStop: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            TextField(title, text: $previewText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
            HStack {
                Button(action: onSpeak) { Label(NSLocalizedString("common.play", comment: ""), systemImage: "play.fill") }
                    .buttonStyle(.borderedProminent)
                Button(action: onStop) { Label(NSLocalizedString("common.stop", comment: ""), systemImage: "stop.fill") }
                    .buttonStyle(.bordered)
            }
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

struct StartupModeCard: View {
    let title: String
    let description: String
    let icon: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.title)
                    .foregroundColor(.blue)

                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .foregroundColor(.primary)
                    Text(description)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                        .font(.title2)
                }
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 2)
                    )
            )
        }
        .buttonStyle(PlainButtonStyle())
    }
}

// MARK: - Startup Mode Tour Component
struct StartupModeTourView: View {
    let mode: String
    let onContinue: () -> Void
    let onBack: () -> Void

    var isKeyboard: Bool { mode == "keyboard" }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: isKeyboard ? "keyboard" : "square.grid.3x3.fill")
                    .font(.system(size: 50))
                    .foregroundColor(.blue)

                Text(NSLocalizedString("welcome_mode.tour.title", comment: ""))
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundColor(.blue)

                Text(isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.title", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.title", comment: ""))
                    .font(.title2)
                    .fontWeight(.bold)

                Text(isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.subtitle", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.subtitle", comment: ""))
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)

                if isKeyboard {
                    TourPhrasePreviewCard()
                }

                VStack(spacing: 12) {
                    TourStepCard(stepNumber: 1, text: isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.step1", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.step1", comment: ""))
                    TourStepCard(stepNumber: 2, text: isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.step2", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.step2", comment: ""))
                    TourStepCard(stepNumber: 3, text: isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.step3", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.step3", comment: ""))
                }

                Button(action: onContinue) {
                    Text(NSLocalizedString("common.continue", comment: ""))
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .cornerRadius(12)
                }
                .padding(.top, 8)

                Button(action: onBack) {
                    Text(NSLocalizedString("welcome_mode.tour.back", comment: ""))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
        }
    }
}

struct TourStepCard: View {
    let stepNumber: Int
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Text("\(stepNumber)")
                .font(.headline)
                .foregroundColor(.blue)
                .frame(width: 28, height: 28)
                .background(Circle().fill(Color.blue.opacity(0.1)))

            Text(text)
                .font(.subheadline)
                .foregroundColor(.primary)

            Spacer()
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

struct TourPhrasePreviewCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(NSLocalizedString("welcome_mode.tour.phrase_preview_title", comment: ""))
                .font(.caption)
                .foregroundColor(.secondary)

            Text(NSLocalizedString("welcome_mode.tour.phrase_preview_message", comment: ""))
                .font(.body)
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color(.systemBackground)))

            HStack(spacing: 8) {
                Text("welcome_mode.tour.sample.hello").font(.caption).bold().padding(.vertical, 6).padding(.horizontal, 12).background(Color.blue.opacity(0.15)).cornerRadius(6)
                Text("welcome_mode.tour.sample.yes").font(.caption).bold().padding(.vertical, 6).padding(.horizontal, 12).background(Color.blue.opacity(0.15)).cornerRadius(6)
                Text("welcome_mode.tour.sample.help").font(.caption).bold().padding(.vertical, 6).padding(.horizontal, 12).background(Color.blue.opacity(0.15)).cornerRadius(6)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.tertiarySystemBackground))
        )
    }
}
