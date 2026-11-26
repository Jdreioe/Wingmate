import SwiftUI
import Shared
import PhotosUI
import UniformTypeIdentifiers

extension SymbolBoardWorkspaceView {
    @ViewBuilder
    func boardManagementSheet(_ sheet: BoardManagementSheet) -> some View {
        NavigationStack {
            Form {
                switch sheet {
                case .renameSet, .renameBoard:
                    Section {
                        TextField("boardset.name_placeholder", text: $managementName)
                            .textInputAutocapitalization(.words)
                    }
                case .resizeBoard:
                    Section {
                        Stepper(value: $managementRows, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.rows", comment: ""), managementRows))
                        }
                        Stepper(value: $managementColumns, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.columns", comment: ""), managementColumns))
                        }
                    } header: {
                        Text("boardset.grid")
                    } footer: {
                        Text("boardset.resize_warning")
                    }
                case .backgroundColor:
                    Section {
                        Toggle("boardset.background_custom", isOn: $useCustomManagementBackground)
                        if useCustomManagementBackground {
                            ColorPicker(
                                "boardset.background_color",
                                selection: $managementBackgroundColor,
                                supportsOpacity: false
                            )
                        }
                    } footer: {
                        Text("boardset.background_footer")
                    }
                }
            }
            .navigationTitle(Text(managementTitle(sheet)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { managementSheet = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        Task {
                            switch sheet {
                            case .renameSet:
                                await model.renameSelectedBoardSet(managementName)
                            case .renameBoard:
                                await model.renameSelectedBoard(managementName)
                            case .resizeBoard:
                                await model.resizeSelectedBoard(rows: managementRows, columns: managementColumns)
                            case .backgroundColor:
                                await model.setSelectedBoardBackgroundColor(
                                    useCustomManagementBackground ? hexFromColor(managementBackgroundColor) : nil
                                )
                            }
                            managementSheet = nil
                        }
                    }
                    .disabled((sheet == .renameSet || sheet == .renameBoard) && managementName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    func managementTitle(_ sheet: BoardManagementSheet) -> LocalizedStringKey {
        switch sheet {
        case .renameSet: "boardset.rename_set"
        case .renameBoard: "boardset.rename_board"
        case .resizeBoard: "boardset.resize_board"
        case .backgroundColor: "boardset.background_color"
        }
    }

    var speechImportSheet: some View {
        let pages = model.previewSpeech(name: speechName, text: speechText, paragraphsPerPage: speechParagraphsPerPage)
        return NavigationStack {
            Form {
                Section {
                    Text("speech_import.help")
                    TextField(NSLocalizedString("speech_import.name", comment: ""), text: $speechName)
                    TextEditor(text: $speechText)
                        .frame(minHeight: 180)
                        .accessibilityLabel(Text("speech_import.text"))
                    Stepper(value: $speechParagraphsPerPage, in: 1...8) {
                        Text(String(format: NSLocalizedString("speech_import.count", comment: ""), speechParagraphsPerPage))
                    }
                }
                Section {
                    ForEach(Array(pages.enumerated()), id: \.offset) { index, page in
                        VStack(alignment: .leading, spacing: 12) {
                            Text("\(index + 1). \(page.name)").font(.headline)
                            ForEach(Array(page.paragraphs.enumerated()), id: \.offset) { _, paragraph in
                                Text(paragraph)
                            }
                        }
                    }
                } header: {
                    Text(String(format: NSLocalizedString("speech_import.preview", comment: ""), pages.count))
                }
            }
            .disabled(model.isCreatingBoardSet)
            .navigationTitle(Text("speech_import.title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { showSpeechImport = false }
                        .disabled(model.isCreatingBoardSet)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.isCreatingBoardSet {
                        ProgressView()
                    } else {
                        Button("speech_import.create") {
                            speechImportFailed = false
                            Task {
                                if let id = await model.importSpeech(
                                    name: speechName, text: speechText, paragraphsPerPage: speechParagraphsPerPage
                                ) {
                                    showSpeechImport = false
                                    speechName = ""
                                    speechText = ""
                                    route = .workspace(boardSetId: id, mode: .run)
                                } else {
                                    speechImportFailed = true
                                }
                            }
                        }
                        .disabled(speechName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || pages.isEmpty)
                    }
                }
            }
            .alert("speech_import.error", isPresented: $speechImportFailed) {
                Button("common.ok", role: .cancel) { }
            }
            .interactiveDismissDisabled(model.isCreatingBoardSet)
        }
    }

    var createBoardsetSheet: some View {
        NavigationStack {
            Form {
                Section("boardset.name") {
                    TextField(NSLocalizedString("boardset.name_placeholder", comment: ""), text: $createBoardsetName)
                }
                Section("boardset.create_kind") {
                    Picker("boardset.create_kind", selection: $createBoardsetKind) {
                        Text("boardset.create_blank").tag(BoardSetCreationKind.blank)
                        Text("boardset.create_keyboard_qwerty").tag(BoardSetCreationKind.qwerty)
                        Text("boardset.create_keyboard_alphabetical").tag(BoardSetCreationKind.alphabetical)
                    }
                }
                if createBoardsetKind == .blank {
                    Section("boardset.grid") {
                        Stepper(value: $createBoardsetRows, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.rows", comment: ""), createBoardsetRows))
                        }
                        Stepper(value: $createBoardsetColumns, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.columns", comment: ""), createBoardsetColumns))
                        }
                    }
                }
            }
            .navigationTitle(Text("boardset.create"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") {
                        showCreateBoardsetSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        showCreateBoardsetSheet = false
                        Task {
                            if createBoardsetKind == .blank {
                                await model.createBoardSet(name: createBoardsetName, rows: createBoardsetRows, columns: createBoardsetColumns)
                            } else {
                                await model.createKeyboardBoardSet(name: createBoardsetName, preset: createBoardsetKind.rawValue)
                            }
                            createBoardsetName = ""
                            createBoardsetRows = 4
                            createBoardsetColumns = 4
                            createBoardsetKind = .blank
                        }
                    }
                }
            }
        }
    }

    var addBoardSheet: some View {
        NavigationStack {
            Form {
                Section("boardset.board_name") {
                    TextField(NSLocalizedString("boardset.board_name_placeholder", comment: ""), text: $addBoardName)
                }
                Section("boardset.grid") {
                    Stepper(value: $addBoardRows, in: 1...12) {
                        Text(String(format: NSLocalizedString("boardset.rows", comment: ""), addBoardRows))
                    }
                    Stepper(value: $addBoardColumns, in: 1...12) {
                        Text(String(format: NSLocalizedString("boardset.columns", comment: ""), addBoardColumns))
                    }
                }
                if model.selectedBoardKeyboardLayout != nil {
                    Section("boardset.keyboard_layout") {
                        Picker("boardset.keyboard_layout", selection: $addBoardKeyboardLayout) {
                            Text("boardset.create_keyboard_qwerty").tag("qwerty")
                            Text("boardset.create_keyboard_alphabetical").tag("alphabetical")
                            Text("boardset.keyboard_symbols").tag("symbols")
                        }
                    }
                }
            }
            .navigationTitle(Text("boardset.add_board"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") {
                        showAddBoardSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        Task {
                            if model.selectedBoardKeyboardLayout != nil {
                                await model.addKeyboardBoardToSelectedSet(
                                    name: addBoardName,
                                    rows: addBoardRows,
                                    columns: addBoardColumns,
                                    layout: addBoardKeyboardLayout
                                )
                            } else {
                                await model.addBoardToSelectedSet(name: addBoardName, rows: addBoardRows, columns: addBoardColumns)
                            }
                            addBoardName = ""
                            addBoardRows = 4
                            addBoardColumns = 4
                            showAddBoardSheet = false
                        }
                    }
                    .disabled(!model.canEditSelectedBoardSet)
                }
            }
        }
    }

    var editCellSheet: some View {
        NavigationStack {
            Form {
                Section("boardset.cell.label") {
                    TextField(NSLocalizedString("boardset.cell.label", comment: ""), text: $editingLabel)
                }

                Section("boardset.cell.vocalization") {
                    TextField(NSLocalizedString("boardset.cell.vocalization", comment: ""), text: $editingVocalization)
                }

                Section {
                    Toggle("boardset.cell.hidden", isOn: $editingHidden)
                } footer: {
                    Text("boardset.cell.hidden_footer")
                }

                if model.speech.ttsEngine.contains("AZURE") {
                    Section {
                        Toggle("boardset.cell.math", isOn: $editingMathMode)
                    } footer: {
                        Text("boardset.cell.math_footer")
                    }
                }

                if model.canEditSelectedBoardSet, editingCellHasButton {
                    Section {
                        HStack(spacing: 12) {
                            fieldNudgeButton("boardset.cell.move.up", systemImage: "arrow.up") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: -1, directionColumn: 0, fromEditor: true) }
                            }
                            fieldNudgeButton("boardset.cell.move.down", systemImage: "arrow.down") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: 1, directionColumn: 0, fromEditor: true) }
                            }
                            fieldNudgeButton("boardset.cell.move.left", systemImage: "arrow.left") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: 0, directionColumn: -1, fromEditor: true) }
                            }
                            fieldNudgeButton("boardset.cell.move.right", systemImage: "arrow.right") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: 0, directionColumn: 1, fromEditor: true) }
                            }
                        }
                        .frame(maxWidth: .infinity)
                    } header: {
                        Text("boardset.cell.move")
                    } footer: {
                        Text("boardset.cell.move.footer")
                    }

                    Section {
                        spanAdjustRow(
                            title: "boardset.cell.size.width",
                            value: currentEditingSpan.columns,
                            decreaseKey: "boardset.cell.size.narrower",
                            increaseKey: "boardset.cell.size.wider",
                            canDecrease: spanIsAllowed(rows: currentEditingSpan.rows, columns: currentEditingSpan.columns - 1),
                            canIncrease: spanIsAllowed(rows: currentEditingSpan.rows, columns: currentEditingSpan.columns + 1),
                            decrease: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: 0, deltaColumns: -1, fromEditor: true) } },
                            increase: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: 0, deltaColumns: 1, fromEditor: true) } }
                        )
                        spanAdjustRow(
                            title: "boardset.cell.size.height",
                            value: currentEditingSpan.rows,
                            decreaseKey: "boardset.cell.size.shorter",
                            increaseKey: "boardset.cell.size.taller",
                            canDecrease: spanIsAllowed(rows: currentEditingSpan.rows - 1, columns: currentEditingSpan.columns),
                            canIncrease: spanIsAllowed(rows: currentEditingSpan.rows + 1, columns: currentEditingSpan.columns),
                            decrease: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: -1, deltaColumns: 0, fromEditor: true) } },
                            increase: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: 1, deltaColumns: 0, fromEditor: true) } }
                        )
                    } header: {
                        Text("boardset.cell.merge")
                    } footer: {
                        Text("boardset.cell.merge_footer")
                    }
                }

                if model.selectedBoardKeyboardLayout != nil {
                    Section("boardset.cell.keyboard_action") {
                        TextField("boardset.cell.insert_text", text: $editingInsertedText)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                            .onChange(of: editingInsertedText) { _, text in
                                editingInsertedTextFollowsLabel = false
                                if !text.isEmpty { editingActions = [] }
                            }
                    }
                }

                Section {
                        ForEach(BoardKeyAction.allCases.filter { $0 != .none }) { action in
                            Toggle(
                                action.localizationKey,
                                isOn: Binding(
                                    get: { editingActions.contains(action.rawValue) },
                                    set: { enabled in
                                        if enabled {
                                            editingInsertedText = ""
                                            editingInsertedTextFollowsLabel = false
                                            if !editingActions.contains(action.rawValue) {
                                                editingActions.append(action.rawValue)
                                            }
                                        } else {
                                            editingActions.removeAll { $0 == action.rawValue }
                                        }
                                    }
                                )
                            )
                        }
                        if editingActions.count > 1 {
                            ForEach(Array(editingActions.enumerated()), id: \.offset) { index, rawAction in
                                HStack {
                                    Text(boardKeyActionLabel(rawAction))
                                    Spacer()
                                    Button {
                                        moveEditingAction(at: index, by: -1)
                                    } label: {
                                        Image(systemName: "arrow.up")
                                    }
                                    .disabled(index == 0)
                                    .accessibilityLabel(Text("boardset.cell.action.move_up"))
                                    Button {
                                        moveEditingAction(at: index, by: 1)
                                    } label: {
                                        Image(systemName: "arrow.down")
                                    }
                                    .disabled(index == editingActions.count - 1)
                                    .accessibilityLabel(Text("boardset.cell.action.move_down"))
                                }
                            }
                        }
                } header: {
                    Text("boardset.cell.special_actions")
                } footer: {
                    Text("boardset.cell.special_actions_footer")
                }

                Section("phrase.symbol.section") {
                    Picker("phrase.symbol.source", selection: $editingSymbolSource) {
                        Text("phrase.symbol.source.opensymbols").tag(CellSymbolSource.openSymbols)
                        Text("phrase.symbol.source.user_photos").tag(CellSymbolSource.userPhotos)
                    }
                    .pickerStyle(.segmented)

                    if editingSymbolSource == .openSymbols {
                        Picker("phrase.symbol.package", selection: $editingSymbolPackage) {
                            ForEach(CellSymbolPackageFilter.allCases) { option in
                                Text(option.localizedTitle).tag(option)
                            }
                        }
                        .pickerStyle(.segmented)

                        HStack(spacing: 8) {
                            TextField(NSLocalizedString("phrase.symbol.search.placeholder", comment: ""), text: $editingSymbolQuery)
                                .textFieldStyle(.roundedBorder)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled(true)
                                .onSubmit {
                                    Task { await searchCellOpenSymbols() }
                                }

                            Button {
                                Task { await searchCellOpenSymbols() }
                            } label: {
                                if isSearchingCellSymbols {
                                    ProgressView()
                                } else {
                                    Text("common.search")
                                }
                            }
                            .disabled(isSearchingCellSymbols || editingSymbolQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }

                        if !editingSymbolResults.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) {
                                    ForEach(editingSymbolResults.prefix(30), id: \.id) { symbol in
                                        Button {
                                            editingSelectedSymbolUrl = symbol.image_url
                                            shouldClearEditingSymbol = false
                                        } label: {
                                            VStack(spacing: 4) {
                                                if let imageUrl = symbol.image_url, let url = URL(string: imageUrl) {
                                                    AsyncImage(url: url) { phase in
                                                        if case .success(let image) = phase {
                                                            image.resizable().scaledToFit()
                                                        } else if case .empty = phase {
                                                            ProgressView()
                                                        } else {
                                                            Image(systemName: "photo").foregroundStyle(.secondary)
                                                        }
                                                    }
                                                    .frame(width: 56, height: 56)
                                                }
                                                Text(symbol.name).font(.caption2).lineLimit(1)
                                                Text(CellSymbolPackageFilter.title(for: symbol.source))
                                                    .font(.caption2)
                                                    .foregroundStyle(.secondary)
                                            }
                                            .frame(width: 92, height: 116)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    } else {
                        HStack(spacing: 8) {
                            PhotosPicker(selection: $editingSelectedPhotoItem, matching: .images, photoLibrary: .shared()) {
                                Label("phrase.symbol.pick_photo", systemImage: "photo.on.rectangle")
                            }
                            .buttonStyle(.bordered)

                            Button(action: { isImportingCellSymbolFile = true }) {
                                Label("phrase.symbol.import_file", systemImage: "folder")
                            }
                            .buttonStyle(.bordered)
                        }
                    }

                    if let selectedUrl = editingSelectedSymbolUrl,
                       let url = URL(string: selectedUrl) {
                        HStack(spacing: 12) {
                            AsyncImage(url: url) { phase in
                                switch phase {
                                case .success(let image):
                                    image
                                        .resizable()
                                        .scaledToFit()
                                case .failure(_):
                                    Image(systemName: "photo")
                                        .foregroundStyle(.secondary)
                                case .empty:
                                    ProgressView()
                                @unknown default:
                                    EmptyView()
                                }
                            }
                            .frame(width: 56, height: 56)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(10)

                            Text("phrase.symbol.selected")
                                .font(.footnote)
                                .foregroundStyle(.secondary)

                            Spacer()

                            Button("common.clear") {
                                editingSelectedSymbolUrl = nil
                                shouldClearEditingSymbol = true
                            }
                            .font(.footnote)
                        }
                    }
                }

                Section {
                    Picker("boardset.cell.behavior", selection: $editingOpensPage) {
                        Text("boardset.cell.behavior.speak").tag(false)
                        Text("boardset.cell.behavior.open_page").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .disabled(linkableBoardIds.isEmpty)
                    if editingOpensPage {
                        Picker("boardset.cell.linked_board", selection: $editingLinkedBoardId) {
                            Text("boardset.cell.choose_page").tag("")
                            ForEach(linkableBoardIds, id: \.self) { boardId in
                                Text(model.boardDisplayName(id: boardId)).tag(boardId)
                            }
                        }
                    }
                } header: {
                    Text("boardset.cell.behavior")
                }

                Section("boardset.cell.shape") {
                    Picker("boardset.cell.shape", selection: $editingShape) {
                        Text("boardset.cell.shape.rounded").tag("rounded")
                        Text("boardset.cell.shape.square").tag("square")
                        Text("boardset.cell.shape.pill").tag("pill")
                        Text("boardset.cell.shape.speech").tag("speech")
                        Text("boardset.cell.shape.thought").tag("thought")
                    }
                }

                Section("boardset.cell.language") {
                    Picker("boardset.cell.language", selection: $editingLanguage) {
                        Text("boardset.cell.language.default").tag("")
                        ForEach(cellLanguageChoices, id: \.tag) { choice in
                            Text(choice.title).tag(choice.tag)
                        }
                    }
                }

                Section {
                    if isRecordingCell {
                        Button("common.stop", role: .destructive) {
                            Task { await stopCellRecording() }
                        }
                    } else {
                        Button(LocalizedStringKey(editingRecordingPath == nil ? "phrase.record" : "phrase.record.replace")) {
                            AVAudioApplication.requestRecordPermission { granted in
                                Task { @MainActor in
                                    guard granted else { cellMicDenied = true; return }
                                    cellMicDenied = false
                                    _ = try? await cellRecorder.startRecording()
                                    isRecordingCell = true
                                }
                            }
                        }
                    }
                    if let path = editingRecordingPath {
                        Button("phrase.play_recording") { cellRecorder.play(url: URL(fileURLWithPath: path)) }
                        Button("common.clear", role: .destructive) {
                            editingRecordingPath = nil
                            editingClearsRecording = true
                        }
                    }
                    if cellMicDenied {
                        Text("phrase.mic_permission_denied")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("phrase.recording")
                }

                Section("boardset.cell.colors") {
                    Toggle("boardset.cell.background_enabled", isOn: $useCustomBackgroundColor)
                    if useCustomBackgroundColor {
                        ColorPicker("boardset.cell.background_picker", selection: $editingBackgroundPickerColor, supportsOpacity: true)
                    }

                    Toggle("boardset.cell.border_enabled", isOn: $useCustomBorderColor)
                    if useCustomBorderColor {
                        ColorPicker("boardset.cell.border_picker", selection: $editingBorderPickerColor, supportsOpacity: true)
                    }
                }

                Section("boardset.cell.word_type") {
                    Picker("boardset.cell.word_type", selection: $editingWordType) {
                        Text("boardset.cell.word_type.automatic").tag("")
                        Text("boardset.cell.word_type.pronoun").tag("pronoun")
                        Text("boardset.cell.word_type.verb").tag("verb")
                        Text("boardset.cell.word_type.descriptor").tag("descriptor")
                        Text("boardset.cell.word_type.noun").tag("noun")
                        Text("boardset.cell.word_type.social").tag("social")
                        Text("boardset.cell.word_type.other").tag("other")
                    }
                }
            }
            .navigationTitle(Text("boardset.cell.edit_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarBackground(Color(.systemGroupedBackground), for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") {
                        if isRecordingCell { Task { await stopCellRecording() } }
                        showEditCellSheet = false
                    }
                }
                if editingCellHasButton {
                    ToolbarItem(placement: .bottomBar) {
                        Button("boardset.cell.clear", role: .destructive) {
                            Task {
                                await model.clearSelectedBoardCell(row: editingRow, col: editingCol)
                                showEditCellSheet = false
                            }
                        }
                        .disabled(!model.canEditSelectedBoardSet)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        Task {
                            let backgroundColorHex = useCustomBackgroundColor ? hexFromColor(editingBackgroundPickerColor) : nil
                            let borderColorHex = useCustomBorderColor ? hexFromColor(editingBorderPickerColor) : nil
                            let followedText = trimmed(editingVocalization) ?? trimmed(editingLabel) ?? ""
                            let insertedText = editingInsertedTextFollowsLabel ? followedText : editingInsertedText
                            let actions = editingActions + (insertedText.isEmpty ? [] : ["+\(insertedText)"])
                            await model.upsertSelectedBoardCell(
                                row: editingRow,
                                col: editingCol,
                                label: editingLabel,
                                vocalization: editingVocalization,
                                backgroundColor: backgroundColorHex,
                                borderColor: borderColorHex,
                                linkedBoardId: editingOpensPage ? editingLinkedBoardId : "",
                                imageUrl: editingSelectedSymbolUrl,
                                clearImage: shouldClearEditingSymbol,
                                actions: actions,
                                wordType: editingWordType,
                                hidden: editingHidden,
                                shape: editingShape,
                                language: editingLanguage,
                                mathMode: editingMathMode,
                                recordingPath: editingClearsRecording ? nil : editingRecordingPath,
                                clearRecording: editingClearsRecording
                            )
                            if case .custom(let span) = editingSpan, editingCellHasButton {
                                await model.resizeSelectedBoardField(
                                    row: editingRow,
                                    col: editingCol,
                                    rows: span.rows,
                                    columns: span.columns
                                )
                            }
                            showEditCellSheet = false
                        }
                    }
                    .disabled(!model.canEditSelectedBoardSet || trimmed(editingLabel) == nil || (editingOpensPage && trimmed(editingLinkedBoardId) == nil))
                }
            }
        }
    }

    var cellLanguageChoices: [(tag: String, title: String)] {
        var choices: [(tag: String, title: String)] = []
        let primary = model.speech.primaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !primary.isEmpty {
            choices.append((primary, String(format: NSLocalizedString("boardset.cell.language.primary", comment: ""), primary)))
        }
        let secondary = model.speech.secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !secondary.isEmpty, secondary != primary {
            choices.append((secondary, String(format: NSLocalizedString("boardset.cell.language.secondary", comment: ""), secondary)))
        }
        let current = editingLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !current.isEmpty, !choices.contains(where: { $0.tag == current }) {
            choices.append((current, current))
        }
        return choices
    }

    func stopCellRecording() async {
        if let url = try? await cellRecorder.stopRecording() {
            editingRecordingPath = url.path
            editingClearsRecording = false
        }
        isRecordingCell = false
    }

    var linkableBoardIds: [String] {
        guard let set = model.selectedBoardSet else { return [] }
        return set.boardIds.filter { $0 != model.selectedBoardId }
    }
}
