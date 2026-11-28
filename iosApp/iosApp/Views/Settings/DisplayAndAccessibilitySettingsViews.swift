import SwiftUI
import Shared

struct DisplaySettingsView: View {
    @ObservedObject var settings: AppSettingsModel
    @Binding var uiTextFieldHeight: Double
    @Binding var uiInputFontSize: Double
    @Binding var uiChipFontSize: Double
    @Binding var uiPlayIconSize: Double
    let onWordTypeColorsChanged: @MainActor () async -> Void
    @AppStorage("partner_display_mode") private var partnerDisplayModeRaw: String = PartnerDisplayMode.whileCommunicating.rawValue

    var body: some View {
        Form {
            Section {
                Picker("settings.partner_display.mode", selection: $partnerDisplayModeRaw) {
                    ForEach(PartnerDisplayMode.allCases) { mode in
                        Text(mode.titleKey).tag(mode.rawValue)
                    }
                }
            } header: {
                Text("settings.partner_display.section")
            } footer: {
                Text("settings.partner_display.footer")
            }

            Section("settings.display.grid") {
                Toggle("settings.display.show_labels", isOn: Binding(
                    get: { settings.showButtonLabels }, set: { settings.setShowButtonLabels($0) }
                ))
                Toggle("settings.display.show_symbols", isOn: Binding(
                    get: { settings.showButtonSymbols }, set: { settings.setShowButtonSymbols($0) }
                ))
                if settings.showButtonLabels && settings.showButtonSymbols {
                    Toggle("settings.display.label_top", isOn: Binding(
                        get: { settings.labelAtTop }, set: { settings.setLabelAtTop($0) }
                    ))
                }
                Stepper(value: Binding(
                    get: { settings.preferredGridColumns }, set: { settings.setPreferredGridColumns($0) }
                ), in: 1...6) {
                    LabeledContent("settings.display.columns", value: "\(settings.preferredGridColumns)")
                }
                Toggle("settings.display.high_contrast", isOn: Binding(
                    get: { settings.highContrastMode }, set: { settings.setHighContrastMode($0) }
                ))
                Toggle("settings.display.word_type_colors", isOn: Binding(
                    get: { settings.wordTypeColorScheme == "Fitzgerald" },
                    set: { settings.setWordTypeColorsEnabled($0, afterSave: onWordTypeColorsChanged) }
                ))
                Toggle("settings.display.message_bar", isOn: Binding(
                    get: { settings.boardShowMessageBar }, set: { settings.setBoardShowMessageBar($0) }
                ))
                Toggle("settings.display.speak_button", isOn: Binding(
                    get: { settings.boardShowSpeakButton }, set: { settings.setBoardShowSpeakButton($0) }
                ))
            }

            Section("settings.display.interface_size") {
                SettingsSliderRow(title: "settings.ui_size.input_height", value: $uiTextFieldHeight, range: 44...160, step: 2)
                SettingsSliderRow(title: "settings.ui_size.input_font", value: $uiInputFontSize, range: 14...30, step: 1)
                SettingsSliderRow(title: "settings.ui_size.chip_font", value: $uiChipFontSize, range: 12...28, step: 1)
                SettingsSliderRow(title: "settings.ui_size.playback_icon", value: $uiPlayIconSize, range: 28...64, step: 1)
            }
        }
        .navigationTitle(Text("settings.category.display"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AccessibilitySettingsView: View {
    @ObservedObject var settings: AppSettingsModel

    var body: some View {
        Form {
            Section("settings.accessibility.touch_timing") {
                SettingsSliderRow(
                    title: "settings.accessibility.hold",
                    value: Binding(get: { settings.holdToSelectMillis }, set: { settings.setHoldToSelectMillis($0) }),
                    range: 0...2_000,
                    step: 100,
                    suffix: " ms"
                )
                SettingsSliderRow(
                    title: "settings.accessibility.dwell",
                    value: Binding(get: { settings.dwellToSelectMillis }, set: { settings.setDwellToSelectMillis($0) }),
                    range: 0...5_000,
                    step: 250,
                    suffix: " ms"
                )
                SettingsSliderRow(
                    title: "settings.accessibility.debounce",
                    value: Binding(get: { settings.selectionDebounceMillis }, set: { settings.setSelectionDebounceMillis($0) }),
                    range: 0...1_000,
                    step: 50,
                    suffix: " ms"
                )
            }

            Section("settings.accessibility.feedback") {
                Toggle("settings.accessibility.touch_down", isOn: Binding(get: { settings.selectOnTouchDown }, set: { settings.setSelectOnTouchDown($0) }))
                Text("settings.accessibility.touch_down.footer").font(.footnote).foregroundStyle(.secondary)
                Toggle("settings.accessibility.swipe_pages", isOn: Binding(get: { settings.swipeBetweenPages }, set: { settings.setSwipeBetweenPages($0) }))
                Text("settings.accessibility.swipe_pages.footer").font(.footnote).foregroundStyle(.secondary)
                Toggle("settings.accessibility.selection_sound", isOn: Binding(
                    get: { settings.selectionSoundEnabled }, set: { settings.setSelectionSoundEnabled($0) }
                ))
                SettingsSliderRow(
                    title: "settings.accessibility.selection_highlight",
                    value: Binding(get: { Double(settings.selectionHighlightMillis) }, set: { settings.setSelectionHighlightMillis($0) }),
                    range: 0...2_000,
                    step: 100,
                    suffix: " ms"
                )
                Picker("settings.speech_policy.title", selection: Binding(
                    get: { settings.speechPolicy }, set: { settings.setSpeechPolicy($0) }
                )) {
                    Text("settings.speech_policy.immediate").tag("Immediate")
                    Text("settings.speech_policy.sentence_only").tag("SentenceOnly")
                }
                .pickerStyle(.menu)
                Toggle("settings.accessibility.auditory_fishing", isOn: Binding(
                    get: { settings.auditoryFishingEnabled }, set: { settings.setAuditoryFishingEnabled($0) }
                ))
            }

            Section("settings.pointer_emphasis.title") {
                Picker("settings.pointer_emphasis.style", selection: Binding(
                    get: { settings.pointerEmphasisStyle }, set: { settings.setPointerEmphasis(style: $0) }
                )) {
                    Text("settings.pointer_emphasis.system").tag("System")
                    Text("settings.pointer_emphasis.ring").tag("Ring")
                    Text("settings.pointer_emphasis.outline").tag("Outline")
                }
                SettingsSliderRow(
                    title: "settings.pointer_emphasis.size",
                    value: Binding(get: { settings.pointerEmphasisScale }, set: { settings.setPointerEmphasis(scale: $0) }),
                    range: 1...3,
                    step: 0.25,
                    decimals: 2
                )
                Text("settings.pointer_emphasis.ios_note").font(.footnote).foregroundStyle(.secondary)
            }

            Section("settings.scanning.title") {
                Toggle("settings.scanning.enable", isOn: Binding(
                    get: { settings.scanningEnabled }, set: { settings.setScanningEnabled($0) }
                ))
                Group {
                    Toggle("settings.scanning.playback_area", isOn: Binding(get: { settings.scanPlaybackAreaEnabled }, set: { settings.setScanPlaybackAreaEnabled($0) }))
                    Toggle("settings.scanning.input_field", isOn: Binding(get: { settings.scanInputFieldEnabled }, set: { settings.setScanInputFieldEnabled($0) }))
                    Toggle("settings.scanning.phrase_grid", isOn: Binding(get: { settings.scanPhraseGridEnabled }, set: { settings.setScanPhraseGridEnabled($0) }))
                    Toggle("settings.scanning.category_items", isOn: Binding(get: { settings.scanCategoryItemsEnabled }, set: { settings.setScanCategoryItemsEnabled($0) }))
                    Toggle("settings.scanning.topbar", isOn: Binding(get: { settings.scanTopBarEnabled }, set: { settings.setScanTopBarEnabled($0) }))
                    Picker("settings.scanning.grid_order", selection: Binding(get: { settings.scanPhraseGridOrder }, set: { settings.setScanPhraseGridOrder($0) })) {
                        Text("settings.scanning.grid_order.row_major").tag("row-major")
                        Text("settings.scanning.grid_order.column_major").tag("column-major")
                        Text("settings.scanning.grid_order.linear").tag("linear")
                    }
                    SettingsSliderRow(title: "settings.scanning.dwell", value: Binding(get: { settings.scanDwellTimeSeconds }, set: { settings.setScanDwellTimeSeconds($0) }), range: 0.3...2.0, step: 0.1, decimals: 1)
                    SettingsSliderRow(title: "settings.scanning.auto_advance", value: Binding(get: { settings.scanAutoAdvanceSeconds }, set: { settings.setScanAutoAdvanceSeconds($0) }), range: 0.5...3.0, step: 0.1, decimals: 1)
                }
                .disabled(!settings.scanningEnabled)
            }
        }
        .navigationTitle(Text("settings.category.accessibility"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
