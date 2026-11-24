import SwiftUI

/// Screen or Page settings, matching Android's inheritance: each choice can follow
/// the level above it, or set Shown/Hidden, activation, and where to go next.
struct BoardSettingsSheet: View {
    @ObservedObject var model: IosViewModel
    let forPage: Bool
    let onClose: () -> Void

    @State private var showLabels = "inherit"
    @State private var showSymbols = "inherit"
    @State private var labelAtTop = "inherit"
    @State private var showMessageBar = "inherit"
    @State private var showSpeakButton = "inherit"
    @State private var messageBarEditable = "inherit"
    @State private var activationBehavior = "inherit"
    @State private var returnBehavior = "inherit"
    @State private var cacheSpeech = "inherit"
    @State private var isSaving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    shownHiddenPicker("board_settings.show_labels", selection: $showLabels)
                    shownHiddenPicker("board_settings.show_symbols", selection: $showSymbols)
                    Picker("board_settings.label_position", selection: $labelAtTop) {
                        Text(inheritTitle).tag("inherit")
                        Text("board_settings.top").tag("on")
                        Text("board_settings.bottom").tag("off")
                    }
                } header: {
                    Text("board_settings.appearance")
                } footer: {
                    Text("board_settings.label_position_footer")
                }

                Section {
                    shownHiddenPicker("board_settings.message_bar", selection: $showMessageBar)
                    shownHiddenPicker("board_settings.speak_button", selection: $showSpeakButton)
                    Picker("board_settings.message_bar_editable", selection: $messageBarEditable) {
                        Text(inheritTitle).tag("inherit")
                        Text("board_settings.editable").tag("on")
                        Text("board_settings.read_only").tag("off")
                    }
                    Picker("board_settings.activation", selection: $activationBehavior) {
                        Text(inheritTitle).tag("inherit")
                        Text("board_settings.activation_speak_add").tag("SpeakAndAdd")
                        Text("board_settings.activation_add").tag("AddOnly")
                        Text("board_settings.activation_speak").tag("SpeakOnly")
                    }
                    Picker("board_settings.after_selection", selection: $returnBehavior) {
                        Text(inheritTitle).tag("inherit")
                        Text("board_settings.return_stay").tag("Stay")
                        Text("board_settings.return_previous").tag("Previous")
                        Text("board_settings.return_start").tag("StartPage")
                    }
                    Picker("board_settings.cache_speech", selection: $cacheSpeech) {
                        Text(inheritTitle).tag("inherit")
                        Text("board_settings.enabled").tag("on")
                        Text("board_settings.disabled").tag("off")
                    }
                } header: {
                    Text("board_settings.communication")
                } footer: {
                    Text("board_settings.cache_speech_footer")
                }

                Section {
                    Button(LocalizedStringKey(forPage ? "board_settings.reset_page" : "board_settings.reset_screen")) {
                        showLabels = "inherit"
                        showSymbols = "inherit"
                        labelAtTop = "inherit"
                        showMessageBar = "inherit"
                        showSpeakButton = "inherit"
                        messageBarEditable = "inherit"
                        activationBehavior = "inherit"
                        returnBehavior = "inherit"
                        cacheSpeech = "inherit"
                    }
                }
            }
            .navigationTitle(Text(LocalizedStringKey(forPage ? "board_settings.page_title" : "board_settings.screen_title")))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarBackground(Color(.systemGroupedBackground), for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { onClose() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") { save() }
                        .disabled(isSaving || !model.canEditSelectedBoardSet)
                }
            }
            .task { await load() }
        }
        .presentationDetents([.large])
        .presentationBackground(Color(.systemGroupedBackground))
        .interactiveDismissDisabled(isSaving)
    }

    private var inheritTitle: String {
        NSLocalizedString(forPage ? "board_settings.use_screen" : "board_settings.use_default", comment: "")
    }

    private func shownHiddenPicker(_ title: LocalizedStringKey, selection: Binding<String>) -> some View {
        Picker(title, selection: selection) {
            Text(inheritTitle).tag("inherit")
            Text("board_settings.shown").tag("on")
            Text("board_settings.hidden").tag("off")
        }
    }

    private func load() async {
        guard let draft = await model.loadBoardSettings(forPage: forPage) else { return }
        showLabels = draft.showLabels
        showSymbols = draft.showSymbols
        labelAtTop = draft.labelAtTop
        showMessageBar = draft.showMessageBar
        showSpeakButton = draft.showSpeakButton
        messageBarEditable = draft.messageBarEditable
        activationBehavior = draft.activationBehavior
        returnBehavior = draft.returnBehavior
        cacheSpeech = draft.cacheSpeech
    }

    private func save() {
        isSaving = true
        Task {
            let saved = await model.saveBoardSettings(
                forPage: forPage,
                showLabels: showLabels,
                showSymbols: showSymbols,
                labelAtTop: labelAtTop,
                showMessageBar: showMessageBar,
                showSpeakButton: showSpeakButton,
                messageBarEditable: messageBarEditable,
                activationBehavior: activationBehavior,
                returnBehavior: returnBehavior,
                cacheSpeech: cacheSpeech
            )
            isSaving = false
            if saved { onClose() }
        }
    }
}
