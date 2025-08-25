import SwiftUI
import Shared
import UIKit

struct RightSettingsPanel: View {
    @ObservedObject var model: IosViewModel
    // Bindings for UI size settings
    @Binding var uiTextFieldHeight: Double
    @Binding var uiInputFontSize: Double
    @Binding var uiChipFontSize: Double
    @Binding var uiPlayIconSize: Double
    let openVoicePicker: () -> Void

    private var languages: [String] {
        let langs = model.availableLanguages
        return langs.isEmpty ? [model.primaryLanguage] : langs
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Settings").font(.title3).bold()

            // TTS Engine selection
            VStack(alignment: .leading, spacing: 8) {
                Text("Text-to-Speech Engine").font(.headline)
                Toggle("Use System TTS", isOn: Binding(
                    get: { model.useSystemTts },
                    set: { model.setUseSystemTts($0) }
                ))
                Toggle("Use System TTS when offline", isOn: Binding(
                    get: { model.useSystemTtsWhenOffline },
                    set: { model.setUseSystemTtsWhenOffline($0) }
                ))
                .disabled(model.useSystemTts) // Disable offline toggle when always using system TTS
            }

            Divider()

            // Voice settings
            VStack(alignment: .leading, spacing: 8) {
                Text("Voice").font(.headline)
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text((model.selectedVoice?.displayName ?? model.selectedVoice?.name) ?? "—")
                        if let v = model.selectedVoice {
                            Text(model.effectiveLanguage(for: v)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Button("Change…", action: openVoicePicker)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Language").font(.headline)
                Picker("Language", selection: Binding(
                    get: { model.primaryLanguage },
                    set: { model.updateLanguage($0) }
                )) {
                    ForEach(languages, id: \.self) { lang in
                        Text(lang).tag(lang)
                    }
                }
                .pickerStyle(.menu)
            }

            Divider().padding(.vertical, 4)

            // UI Size Controls
            VStack(alignment: .leading, spacing: 10) {
                Text("UI Size").font(.headline)
                Group {
                    HStack { Text("Input Height"); Spacer(); Text("\(Int(uiTextFieldHeight))") }
                    Slider(value: $uiTextFieldHeight, in: 44...160, step: 2)
                }
                Group {
                    HStack { Text("Input Font"); Spacer(); Text("\(Int(uiInputFontSize))") }
                    Slider(value: $uiInputFontSize, in: 14...30, step: 1)
                }
                Group {
                    HStack { Text("Chip Font"); Spacer(); Text("\(Int(uiChipFontSize))") }
                    Slider(value: $uiChipFontSize, in: 12...28, step: 1)
                }
                Group {
                    HStack { Text("Playback Icon"); Spacer(); Text("\(Int(uiPlayIconSize))") }
                    Slider(value: $uiPlayIconSize, in: 28...64, step: 1)
                }
            }

            Spacer()
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .overlay(Rectangle().fill(Color(.separator)).frame(width: 1), alignment: .leading)
    }
}
