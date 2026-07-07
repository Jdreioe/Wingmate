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
    let openWelcomeFlow: () -> Void
    let openPronunciation: () -> Void

    private var languages: [String] {
        let langs = model.availableLanguages
        return langs.isEmpty ? [model.primaryLanguage] : langs
    }

    private var secondaryLanguages: [String] {
        let langs = languages.filter { $0 != model.primaryLanguage }
        return langs.isEmpty ? [model.secondaryLanguage] : langs
    }

    private var showsLanguageSettings: Bool {
        model.canChangeVoiceLanguage
    }

    private var scanAreasEnabled: Bool {
        model.scanningEnabled
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("settings.title").font(.title3).bold()

                // TTS Engine selection
                VStack(alignment: .leading, spacing: 8) {
                    Text("settings.tts.title").font(.headline)
                    Toggle("settings.tts.use_system", isOn: Binding(
                        get: { model.useSystemTts },
                        set: { model.setUseSystemTts($0) }
                    ))
                    Toggle("settings.tts.system_when_offline", isOn: Binding(
                    get: { model.useSystemTtsWhenOffline },
                    set: { model.setUseSystemTtsWhenOffline($0) }
                ))
                .disabled(model.useSystemTts) // Disable offline toggle when always using system TTS
            }

            Divider()

            // Voice settings
            VStack(alignment: .leading, spacing: 8) {
                Text("toolbar.voice").font(.headline)
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text((model.selectedVoice?.displayName ?? model.selectedVoice?.name) ?? "—")
                        if let v = model.selectedVoice {
                            Text(model.effectiveLanguage(for: v)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Button("common.change", action: openVoicePicker)
                }
            }

            if showsLanguageSettings {
                VStack(alignment: .leading, spacing: 8) {
                    Text("toolbar.language").font(.headline)
                    Picker("toolbar.language", selection: Binding(
                        get: { model.primaryLanguage },
                        set: { model.updateLanguage($0) }
                    )) {
                        ForEach(languages, id: \.self) { lang in
                            Text(lang).tag(lang)
                        }
                    }
                    .pickerStyle(.menu)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("toolbar.second_language").font(.headline)
                    Picker("toolbar.second_language", selection: Binding(
                        get: { model.secondaryLanguage },
                        set: { model.updateSecondaryLanguage($0) }
                    )) {
                        ForEach(secondaryLanguages, id: \.self) { lang in
                            Text(lang).tag(lang)
                        }
                    }
                    .pickerStyle(.menu)
                }
            }

            Divider().padding(.vertical, 4)

            // Welcome Flow Restart
            Button(action: openWelcomeFlow) {
                HStack {
                    Image(systemName: "questionmark.circle")
                    Text("settings.restart_setup")
                    Spacer()
                }
                .padding(.vertical, 4)
            }

            Divider().padding(.vertical, 4)
            
            // Pronunciation
            Button(action: openPronunciation) {
                HStack {
                    Image(systemName: "character.book.closed")
                    Text("pronunciation.title")
                    Spacer()
                }
                .padding(.vertical, 4)
            }

            Divider().padding(.vertical, 4)

            // Mixing recorded phrases
            VStack(alignment: .leading, spacing: 8) {
                Text("settings.playback.title").font(.headline)
                Toggle("settings.playback.mix_recorded", isOn: Binding(
                    get: { model.mixRecordedPhrasesInSentences },
                    set: { model.setMixRecordedPhrases($0) }
                ))
                .help(NSLocalizedString("settings.playback.mix.help", comment: ""))
            }

            Divider().padding(.vertical, 4)

            // Scanning
            VStack(alignment: .leading, spacing: 10) {
                Text("settings.scanning.title").font(.headline)

                Toggle("settings.scanning.enable", isOn: Binding(
                    get: { model.scanningEnabled },
                    set: { model.setScanningEnabled($0) }
                ))

                Toggle("settings.scanning.playback_area", isOn: Binding(
                    get: { model.scanPlaybackAreaEnabled },
                    set: { model.setScanPlaybackAreaEnabled($0) }
                ))
                .disabled(!scanAreasEnabled)

                Toggle("settings.scanning.input_field", isOn: Binding(
                    get: { model.scanInputFieldEnabled },
                    set: { model.setScanInputFieldEnabled($0) }
                ))
                .disabled(!scanAreasEnabled)

                Toggle("settings.scanning.phrase_grid", isOn: Binding(
                    get: { model.scanPhraseGridEnabled },
                    set: { model.setScanPhraseGridEnabled($0) }
                ))
                .disabled(!scanAreasEnabled)

                Toggle("settings.scanning.category_items", isOn: Binding(
                    get: { model.scanCategoryItemsEnabled },
                    set: { model.setScanCategoryItemsEnabled($0) }
                ))
                .disabled(!scanAreasEnabled)

                Toggle("settings.scanning.topbar", isOn: Binding(
                    get: { model.scanTopBarEnabled },
                    set: { model.setScanTopBarEnabled($0) }
                ))
                .disabled(!scanAreasEnabled)

                VStack(alignment: .leading, spacing: 6) {
                    Text("settings.scanning.grid_order")
                    Picker("settings.scanning.grid_order", selection: Binding(
                        get: { model.scanPhraseGridOrder },
                        set: { model.setScanPhraseGridOrder($0) }
                    )) {
                        Text("settings.scanning.grid_order.row_major").tag("row-major")
                        Text("settings.scanning.grid_order.column_major").tag("column-major")
                        Text("settings.scanning.grid_order.linear").tag("linear")
                    }
                    .pickerStyle(.menu)
                    .disabled(!scanAreasEnabled || !model.scanPhraseGridEnabled)
                }

                Group {
                    HStack { Text("settings.scanning.dwell"); Spacer(); Text(String(format: "%.1f", model.scanDwellTimeSeconds)) }
                    Slider(value: Binding(
                        get: { model.scanDwellTimeSeconds },
                        set: { model.setScanDwellTimeSeconds($0) }
                    ), in: 0.3...2.0, step: 0.1)
                }
                .disabled(!scanAreasEnabled)

                Group {
                    HStack { Text("settings.scanning.auto_advance"); Spacer(); Text(String(format: "%.1f", model.scanAutoAdvanceSeconds)) }
                    Slider(value: Binding(
                        get: { model.scanAutoAdvanceSeconds },
                        set: { model.setScanAutoAdvanceSeconds($0) }
                    ), in: 0.5...3.0, step: 0.1)
                }
                .disabled(!scanAreasEnabled)
            }

            Divider().padding(.vertical, 4)

            // UI Size Controls
            VStack(alignment: .leading, spacing: 10) {
                Text("ui_size.title").font(.headline)
                Group {
                    HStack { Text("settings.ui_size.input_height"); Spacer(); Text("\(Int(uiTextFieldHeight))") }
                    Slider(value: $uiTextFieldHeight, in: 44...160, step: 2)
                }
                Group {
                    HStack { Text("settings.ui_size.input_font"); Spacer(); Text("\(Int(uiInputFontSize))") }
                    Slider(value: $uiInputFontSize, in: 14...30, step: 1)
                }
                Group {
                    HStack { Text("settings.ui_size.chip_font"); Spacer(); Text("\(Int(uiChipFontSize))") }
                    Slider(value: $uiChipFontSize, in: 12...28, step: 1)
                }
                Group {
                    HStack { Text("settings.ui_size.playback_icon"); Spacer(); Text("\(Int(uiPlayIconSize))") }
                    Slider(value: $uiPlayIconSize, in: 28...64, step: 1)
                }
            }

            Spacer(minLength: 0)
            }
        }
        .padding(.top, 16)
        .padding(.bottom, 16)
        .padding(.leading, 16)
        .padding(.trailing, 60) // Much larger right padding to ensure no cutoff
        .background(.ultraThinMaterial) // frosted glass material
        .overlay(
            Rectangle()
                .fill(Color(.separator))
                .frame(width: 1), alignment: .leading
        )
        .overlay(
            RoundedRectangle(cornerRadius: 0)
                .stroke(Color.white.opacity(0.15), lineWidth: 0.5)
        )
    }
}
