import SwiftUI
import Shared

struct DisplaySettingsView: View {
    @ObservedObject var model: IosViewModel
    @Binding var uiTextFieldHeight: Double
    @Binding var uiInputFontSize: Double
    @Binding var uiChipFontSize: Double
    @Binding var uiPlayIconSize: Double
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
                    get: { model.showButtonLabels }, set: { model.setShowButtonLabels($0) }
                ))
                Toggle("settings.display.show_symbols", isOn: Binding(
                    get: { model.showButtonSymbols }, set: { model.setShowButtonSymbols($0) }
                ))
                if model.showButtonLabels && model.showButtonSymbols {
                    Toggle("settings.display.label_top", isOn: Binding(
                        get: { model.labelAtTop }, set: { model.setLabelAtTop($0) }
                    ))
                }
                Stepper(value: Binding(
                    get: { model.preferredGridColumns }, set: { model.setPreferredGridColumns($0) }
                ), in: 1...6) {
                    LabeledContent("settings.display.columns", value: "\(model.preferredGridColumns)")
                }
                Toggle("settings.display.high_contrast", isOn: Binding(
                    get: { model.highContrastMode }, set: { model.setHighContrastMode($0) }
                ))
                Toggle("settings.display.word_type_colors", isOn: Binding(
                    get: { model.wordTypeColorScheme == "Fitzgerald" },
                    set: { model.setWordTypeColorsEnabled($0) }
                ))
                Toggle("settings.display.message_bar", isOn: Binding(
                    get: { model.boardShowMessageBar }, set: { model.setBoardShowMessageBar($0) }
                ))
                Toggle("settings.display.speak_button", isOn: Binding(
                    get: { model.boardShowSpeakButton }, set: { model.setBoardShowSpeakButton($0) }
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
    @ObservedObject var model: IosViewModel

    var body: some View {
        Form {
            Section("settings.accessibility.touch_timing") {
                SettingsSliderRow(
                    title: "settings.accessibility.hold",
                    value: Binding(get: { model.holdToSelectMillis }, set: { model.setHoldToSelectMillis($0) }),
                    range: 0...2_000,
                    step: 100,
                    suffix: " ms"
                )
                SettingsSliderRow(
                    title: "settings.accessibility.dwell",
                    value: Binding(get: { model.dwellToSelectMillis }, set: { model.setDwellToSelectMillis($0) }),
                    range: 0...5_000,
                    step: 250,
                    suffix: " ms"
                )
                SettingsSliderRow(
                    title: "settings.accessibility.dwell_rearm",
                    value: Binding(get: { model.dwellRearmDelayMillis }, set: { model.setDwellRearmDelayMillis($0) }),
                    range: 0...500,
                    step: 50,
                    suffix: " ms"
                )
                Text("settings.accessibility.dwell_rearm.footer").font(.footnote).foregroundStyle(.secondary)
                SettingsSliderRow(
                    title: "settings.accessibility.debounce",
                    value: Binding(get: { model.selectionDebounceMillis }, set: { model.setSelectionDebounceMillis($0) }),
                    range: 0...1_000,
                    step: 50,
                    suffix: " ms"
                )
            }

            Section("settings.accessibility.feedback") {
                Toggle("settings.accessibility.touch_down", isOn: Binding(get: { model.selectOnTouchDown }, set: { model.setSelectOnTouchDown($0) }))
                Text("settings.accessibility.touch_down.footer").font(.footnote).foregroundStyle(.secondary)
                Toggle("settings.accessibility.touch_exit", isOn: Binding(get: { model.selectOnTouchExit }, set: { model.setSelectOnTouchExit($0) }))
                Text("settings.accessibility.touch_exit.footer").font(.footnote).foregroundStyle(.secondary)
                Toggle("settings.accessibility.swipe_pages", isOn: Binding(get: { model.swipeBetweenPages }, set: { model.setSwipeBetweenPages($0) }))
                Text("settings.accessibility.swipe_pages.footer").font(.footnote).foregroundStyle(.secondary)
                Toggle("settings.accessibility.selection_sound", isOn: Binding(
                    get: { model.selectionSoundEnabled }, set: { model.setSelectionSoundEnabled($0) }
                ))
                SettingsSliderRow(
                    title: "settings.accessibility.selection_highlight",
                    value: Binding(get: { Double(model.selectionHighlightMillis) }, set: { model.setSelectionHighlightMillis($0) }),
                    range: 0...2_000,
                    step: 100,
                    suffix: " ms"
                )
                Picker("settings.speech_policy.title", selection: Binding(
                    get: { model.speechPolicy }, set: { model.setSpeechPolicy($0) }
                )) {
                    Text("settings.speech_policy.immediate").tag("Immediate")
                    Text("settings.speech_policy.sentence_only").tag("SentenceOnly")
                }
                .pickerStyle(.menu)
                Toggle("settings.accessibility.auditory_fishing", isOn: Binding(
                    get: { model.auditoryFishingEnabled }, set: { model.setAuditoryFishingEnabled($0) }
                ))
            }

            Section("settings.pointer_emphasis.title") {
                Picker("settings.pointer_emphasis.style", selection: Binding(
                    get: { model.pointerEmphasisStyle }, set: { model.setPointerEmphasis(style: $0) }
                )) {
                    Text("settings.pointer_emphasis.system").tag("System")
                    Text("settings.pointer_emphasis.ring").tag("Ring")
                    Text("settings.pointer_emphasis.outline").tag("Outline")
                }
                SettingsSliderRow(
                    title: "settings.pointer_emphasis.size",
                    value: Binding(get: { model.pointerEmphasisScale }, set: { model.setPointerEmphasis(scale: $0) }),
                    range: 1...3,
                    step: 0.25,
                    decimals: 2
                )
                Text("settings.pointer_emphasis.ios_note").font(.footnote).foregroundStyle(.secondary)
            }

            Section("settings.scanning.title") {
                Toggle("settings.scanning.enable", isOn: Binding(
                    get: { model.scanningEnabled }, set: { model.setScanningEnabled($0) }
                ))
                Group {
                    Toggle("settings.scanning.playback_area", isOn: Binding(get: { model.scanPlaybackAreaEnabled }, set: { model.setScanPlaybackAreaEnabled($0) }))
                    Toggle("settings.scanning.input_field", isOn: Binding(get: { model.scanInputFieldEnabled }, set: { model.setScanInputFieldEnabled($0) }))
                    Toggle("settings.scanning.phrase_grid", isOn: Binding(get: { model.scanPhraseGridEnabled }, set: { model.setScanPhraseGridEnabled($0) }))
                    Toggle("settings.scanning.category_items", isOn: Binding(get: { model.scanCategoryItemsEnabled }, set: { model.setScanCategoryItemsEnabled($0) }))
                    Toggle("settings.scanning.topbar", isOn: Binding(get: { model.scanTopBarEnabled }, set: { model.setScanTopBarEnabled($0) }))
                    Picker("settings.scanning.grid_order", selection: Binding(get: { model.scanPhraseGridOrder }, set: { model.setScanPhraseGridOrder($0) })) {
                        Text("settings.scanning.grid_order.row_major").tag("row-major")
                        Text("settings.scanning.grid_order.column_major").tag("column-major")
                        Text("settings.scanning.grid_order.linear").tag("linear")
                    }
                    SettingsSliderRow(title: "settings.scanning.dwell", value: Binding(get: { model.scanDwellTimeSeconds }, set: { model.setScanDwellTimeSeconds($0) }), range: 0.3...2.0, step: 0.1, decimals: 1)
                    SettingsSliderRow(title: "settings.scanning.auto_advance", value: Binding(get: { model.scanAutoAdvanceSeconds }, set: { model.setScanAutoAdvanceSeconds($0) }), range: 0.5...3.0, step: 0.1, decimals: 1)
                }
                .disabled(!model.scanningEnabled)
            }
        }
        .navigationTitle(Text("settings.category.accessibility"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
