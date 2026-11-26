import SwiftUI
import Shared

extension SymbolBoardWorkspaceView {
    // MARK: - Workspace View (Run & Edit Modes)
    @ViewBuilder
    func workspaceView(boardSetId: String, mode: BoardWorkspaceMode) -> some View {
        GeometryReader { workspaceProxy in
            let compactEditHeader = workspaceProxy.size.width < 580
            let occlusionTop = mode == .edit ? max(0, workspaceProxy.topOcclusionClearance - workspaceProxy.safeAreaInsets.top) : 0
            VStack(alignment: .leading, spacing: 12) {
                // Neither mode shows the app's navigation bar. Wide screens put edit controls
                // on one row; compact widths give the Pages strip its own row so it stays usable.
                if mode == .edit {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Button(action: {
                                route = .library
                            }) {
                                // Phones keep only the chevron so the header stays on one line.
                                HStack(spacing: 4) {
                                    Image(systemName: "chevron.left")
                                    if horizontalSizeClass == .regular {
                                        Text(NSLocalizedString("board_workspace.exit_library", comment: ""))
                                    }
                                }
                                .font(.headline)
                                .foregroundColor(.blue)
                                .frame(minWidth: 44, minHeight: 44)
                            }
                            .accessibilityLabel(Text("board_workspace.exit_library"))

                            // The Screen's own actions sit on its title.
                            Menu {
                                Button {
                                    managementName = model.selectedBoardSet?.name ?? ""
                                    managementSheet = .renameSet
                                } label: {
                                    Label("boardset.rename_set", systemImage: "pencil")
                                }
                                Button {
                                    settingsTarget = .screen
                                } label: { Label("board_settings.screen_title", systemImage: "gearshape") }
                            } label: {
                                HStack(spacing: 4) {
                                    Text(String(format: NSLocalizedString("board_workspace.editing", comment: ""), model.selectedBoardSet?.name ?? ""))
                                        .lineLimit(1)
                                    Image(systemName: "chevron.down").font(.caption.weight(.semibold)).accessibilityHidden(true)
                                }
                                .font(.headline)
                                .foregroundStyle(Color.primary)
                                .frame(minHeight: 44)
                            }
                            .layoutPriority(1)
                            .accessibilityAddTraits(.isHeader)

                            if !compactEditHeader {
                                pageStrip(boardSetId: boardSetId)
                            } else {
                                Spacer(minLength: 0)
                            }

                            Button(action: {
                                route = .workspace(boardSetId: boardSetId, mode: .run)
                            }) {
                                Text(NSLocalizedString("board_workspace.finish", comment: ""))
                                    .font(.headline)
                                    .foregroundColor(.blue)
                                    .frame(minHeight: 44)
                            }
                            .fixedSize()
                        }
                        .padding(.horizontal)
                        .padding(.top, 8)

                        if compactEditHeader {
                            pageStrip(boardSetId: boardSetId)
                                .padding(.horizontal, 8)
                        }
                    }
                }
                if model.selectedBoardSet != nil {
                    // Use mode's top, Grid 3 style: a slim menu row where the clock was,
                    // then the message bar with the Screen's own buttons. Edit mode shows
                    // the same bar so those buttons can be chosen in place.
                    if mode == .run {
                        HStack {
                            workspaceMenu(boardSetId: boardSetId)
                            Spacer(minLength: 0)
                            if (model.selectedBoardSet?.boardIds.count ?? 0) > 1 {
                                pageNavigation
                            }
                        }
                        // Clear of the rounded corner when it sits beside the Dynamic Island.
                        .padding(.horizontal, horizontalSizeClass == .regular ? 16 : 28)
                        if model.boardMessageBarVisible {
                            HStack(alignment: .center, spacing: 8) {
                                ScreensMessageBar(
                                    model: model,
                                    tokens: boardSentenceTokens,
                                    showSpeakControl: false,
                                    onDelete: { index in
                                        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                                            model.removeMessagePart(at: index)
                                        }
                                        activeSentenceAnimation = nil
                                    },
                                    onSpeak: { model.speakBoardMessage(boardSetId: boardSetId) },
                                    animationNamespace: sentenceAnimationNamespace,
                                    animatedTokenId: activeSentenceAnimation?.tokenId,
                                    onSpeakPart: { model.speakMessagePart(at: $0) }
                                )
                                messageControls(boardSetId: boardSetId, editing: false)
                            }
                            .padding(.horizontal)
                        }
                    } else if model.boardMessageBarVisible {
                        HStack(alignment: .center, spacing: 8) {
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .fill(Color(.secondarySystemFill))
                                .frame(maxWidth: .infinity)
                                .frame(height: horizontalSizeClass == .regular ? 120 : 104)
                                .accessibilityHidden(true)
                            messageControls(boardSetId: boardSetId, editing: true)
                        }
                        .padding(.horizontal)
                    }

                    boardPreview(isEditMode: mode == .edit)
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                }
            }
            .padding(.top, occlusionTop)
            .frame(width: workspaceProxy.size.width, height: workspaceProxy.size.height, alignment: .top)
        }
        .animation(.easeInOut(duration: 0.2), value: model.selectedBoardId)
        .task(id: boardPredictionTaskId) {
            await model.refreshBoardPredictions(context: boardSentenceText)
        }
        .onDisappear { model.stopBoardPredictions() }
        .onChange(of: model.selectedBoardId) { _, _ in
            guard mode == .run else { return }
            UIAccessibility.post(
                notification: .screenChanged,
                argument: model.selectedBoard?.name ?? NSLocalizedString("boardset.untitled_board", comment: "")
            )
        }
        .toolbar(.hidden, for: .navigationBar)
        .statusBarHidden(true)
        // With the status bar hidden, use mode's Menu row moves up beside the
        // Dynamic Island, where the clock was. Only the top edge: in landscape the
        // cutout is on the side and keeps its inset.
        .ignoresSafeArea(.container, edges: mode == .run ? .top : [])
    }

    func pageChip(id: String, isStartPage: Bool, isSelected: Bool) -> some View {
        HStack(spacing: 5) {
            if isStartPage {
                Image(systemName: "house.fill").font(.caption2)
            }
            Text(model.boardDisplayName(id: id))
                .font(.subheadline)
                .fontWeight(isSelected ? .bold : .regular)
            if isSelected {
                Image(systemName: "chevron.down").font(.caption2.weight(.semibold)).accessibilityHidden(true)
            }
        }
        .foregroundStyle(Color.primary)
        .padding(.horizontal, 12)
        .frame(minHeight: 36)
        .background(isSelected ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
        .clipShape(Capsule())
    }

    /// Actions for the current Page, opened from its chip.
    @ViewBuilder
    func pageActions(isStartPage: Bool) -> some View {
        Button {
            managementName = model.selectedBoard?.name ?? ""
            managementSheet = .renameBoard
        } label: {
            Label("boardset.rename_board", systemImage: "pencil")
        }
        Button {
            managementRows = max(1, Int(model.selectedBoard?.grid?.rows ?? 4))
            managementColumns = max(1, Int(model.selectedBoard?.grid?.columns ?? 4))
            managementSheet = .resizeBoard
        } label: {
            Label("boardset.resize_board", systemImage: "square.grid.3x3")
        }
        Button {
            if let value = model.selectedBoard?.backgroundColor {
                managementBackgroundColor = colorFromHex(value, fallback: Color(.secondarySystemBackground))
                useCustomManagementBackground = true
            } else {
                managementBackgroundColor = Color(.secondarySystemBackground)
                useCustomManagementBackground = false
            }
            managementSheet = .backgroundColor
        } label: {
            Label("boardset.background_color", systemImage: "paintpalette")
        }
        Button {
            backgroundFit = model.boardBackgroundImageFit
            showBackgroundEditor = true
        } label: { Label("boardset.background_image", systemImage: "photo") }
        Button {
            settingsTarget = .page
        } label: { Label("board_settings.page_title", systemImage: "slider.horizontal.3") }
        if !isStartPage {
            Button {
                Task { await model.makeSelectedBoardRoot() }
            } label: {
                Label("boardset.make_home", systemImage: "house")
            }
            Divider()
            Button(role: .destructive) {
                showDeleteBoardConfirmation = true
            } label: {
                Label("boardset.delete_board", systemImage: "trash")
            }
        }
    }

    /// Edit mode's Pages, for quick switching, ending with Add Page. The current
    /// Page's chip opens its actions.
    func pageStrip(boardSetId: String) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let selectedSet = model.selectedBoardSet {
                    ForEach(selectedSet.boardIds, id: \.self) { id in
                        if model.selectedBoardId == id {
                            Menu { pageActions(isStartPage: id == selectedSet.rootBoardId) } label: {
                                pageChip(id: id, isStartPage: id == selectedSet.rootBoardId, isSelected: true)
                            }
                            .accessibilityAddTraits(.isSelected)
                        } else {
                            Button(action: {
                                Task { await model.selectBoard(id: id, resetStack: true) }
                            }) {
                                pageChip(id: id, isStartPage: id == selectedSet.rootBoardId, isSelected: false)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                Button(action: {
                    addBoardKeyboardLayout = model.selectedBoardKeyboardLayout ?? "qwerty"
                    showAddBoardSheet = true
                }) {
                    Label("boardset.add_board", systemImage: "plus")
                        .font(.subheadline)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 36)
                        .overlay(Capsule().strokeBorder(Color(.separator), style: StrokeStyle(lineWidth: 1, dash: [4])))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }
            .padding(.horizontal, 8)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The Screen's 1–4 buttons beside the message bar. They always fill the same
    /// 2×2 area, so fewer buttons are bigger: one fills it, two stack, three give
    /// the first a full-height column. In edit mode each slot is a menu that
    /// changes or removes its action, and a + slot adds one.
    @ViewBuilder
    func messageControls(boardSetId: String, editing: Bool) -> some View {
        let actions = model.boardMessageBarActions
        let slots: [String?] = actions.map { Optional($0) } + (editing && actions.count < 4 ? [nil] : [])
        let gap: CGFloat = 8
        let full = controlSize * 2 + gap
        let half = controlSize
        let slot = { (index: Int, size: CGSize) in
            messageSlot(slots[index], index: index, actions: actions, editing: editing, boardSetId: boardSetId, size: size)
        }
        switch slots.count {
        case 0:
            EmptyView()
        case 1:
            slot(0, CGSize(width: full, height: full))
        case 2:
            VStack(spacing: gap) {
                slot(0, CGSize(width: full, height: half))
                slot(1, CGSize(width: full, height: half))
            }
        case 3:
            HStack(spacing: gap) {
                slot(0, CGSize(width: half, height: full))
                VStack(spacing: gap) {
                    slot(1, CGSize(width: half, height: half))
                    slot(2, CGSize(width: half, height: half))
                }
            }
        default:
            Grid(horizontalSpacing: gap, verticalSpacing: gap) {
                GridRow {
                    slot(0, CGSize(width: half, height: half))
                    slot(1, CGSize(width: half, height: half))
                }
                GridRow {
                    slot(2, CGSize(width: half, height: half))
                    slot(3, CGSize(width: half, height: half))
                }
            }
        }
    }

    @ViewBuilder
    func messageSlot(_ action: String?, index: Int, actions: [String], editing: Bool, boardSetId: String, size: CGSize) -> some View {
        if let action {
            if editing {
                messageActionEditor(index: index, action: action, actions: actions, size: size)
            } else {
                messageActionButton(action, boardSetId: boardSetId, size: size)
            }
        } else {
            addMessageActionMenu(actions: actions, size: size)
        }
    }

    private static let messageBarActionChoices = ["Speak", "Hold", "DeleteLast", "Clear"]

    func messageActionTitle(_ action: String) -> LocalizedStringKey {
        switch action {
        case "Speak": "playback.play"
        case "Hold": "screens.message_bar.hold"
        case "DeleteLast": "screens.message_bar.delete_last"
        default: "screens.message_bar.clear_all"
        }
    }

    func messageActionIcon(_ action: String) -> String {
        switch action {
        case "Speak": "play.fill"
        case "Hold": "bookmark"
        case "DeleteLast": "delete.left"
        default: "trash"
        }
    }

    @ViewBuilder
    func messageActionButton(_ action: String, boardSetId: String, size: CGSize) -> some View {
        let hasMessage = !model.messageParts.isEmpty
        let scanHidden = model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled
        switch action {
        case "Speak":
            let speaking = ["Playing", "Preparing", "Paused"].contains(model.playback.name)
            controlButton(speaking ? "playback.stop" : "playback.play", systemImage: speaking ? "stop.fill" : "play.fill", prominent: true, size: size) {
                if speaking { model.stopSpeech() } else { model.speakBoardMessage(boardSetId: boardSetId) }
            }
            .disabled(!speaking && !hasMessage)
            .accessibilityHidden(scanHidden)
        case "Hold":
            let isHolding = model.heldMessageText?.isEmpty == false
            controlButton(isHolding ? "typing.held_message.swap" : "screens.message_bar.hold", systemImage: isHolding ? "bookmark.fill" : "bookmark", size: size) {
                withAnimation(.easeOut(duration: 0.2)) { model.toggleHoldThatThought() }
            }
            .disabled(!isHolding && !hasMessage)
            .accessibilityHint(Text(LocalizedStringKey(isHolding ? "accessibility.playback.restore_thought_hint" : "")))
            .accessibilityHidden(scanHidden)
        case "DeleteLast":
            controlButton("screens.message_bar.delete_last", systemImage: "delete.left", size: size) {
                withAnimation(.easeOut(duration: 0.2)) { model.removeLastMessagePart() }
                activeSentenceAnimation = nil
            }
            .disabled(!hasMessage)
            .accessibilityHidden(scanHidden)
        default:
            controlButton("screens.message_bar.clear_all", systemImage: "trash", size: size) {
                withAnimation(.easeOut(duration: 0.2)) { model.clearMessage() }
                activeSentenceAnimation = nil
            }
            .disabled(!hasMessage)
            .accessibilityHidden(scanHidden)
        }
    }

    /// One button slot in edit mode: pick what it does, or remove it (one always stays).
    func messageActionEditor(index: Int, action: String, actions: [String], size: CGSize) -> some View {
        Menu {
            ForEach(Self.messageBarActionChoices, id: \.self) { choice in
                Button {
                    var next = actions
                    next[index] = choice
                    Task { await model.setMessageBarActions(next) }
                } label: {
                    Label(messageActionTitle(choice), systemImage: choice == action ? "checkmark" : messageActionIcon(choice))
                }
                .disabled(choice != action && actions.contains(choice))
            }
            Divider()
            Button(role: .destructive) {
                var next = actions
                next.remove(at: index)
                Task { await model.setMessageBarActions(next) }
            } label: {
                Label("screens.message_bar.remove_button", systemImage: "minus.circle")
            }
            .disabled(actions.count <= 1)
        } label: {
            controlLabel(systemImage: messageActionIcon(action), prominent: action == "Speak", size: size)
        }
        .accessibilityLabel(Text(messageActionTitle(action)))
        .accessibilityHint(Text("screens.message_bar.edit_hint"))
    }

    func addMessageActionMenu(actions: [String], size: CGSize) -> some View {
        Menu {
            ForEach(Self.messageBarActionChoices.filter { !actions.contains($0) }, id: \.self) { choice in
                Button {
                    Task { await model.setMessageBarActions(actions + [choice]) }
                } label: {
                    Label(messageActionTitle(choice), systemImage: messageActionIcon(choice))
                }
            }
        } label: {
            Image(systemName: "plus")
                .font(.system(size: iconSize(for: size), weight: .semibold))
                .frame(width: size.width, height: size.height)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Color(.separator), style: StrokeStyle(lineWidth: 1, dash: [4]))
                )
        }
        .accessibilityLabel(Text("screens.message_bar.add_button"))
    }

    /// Icons grow with their slot, but a wide or tall slot keeps a square-sized icon.
    func iconSize(for size: CGSize) -> CGFloat { min(size.width, size.height) * 0.4 }

    func controlLabel(systemImage: String, prominent: Bool, size: CGSize) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: iconSize(for: size), weight: .semibold))
            .foregroundStyle(prominent ? Color.white : Color.accentColor)
            .frame(width: size.width, height: size.height)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(prominent ? Color.accentColor : Color(.secondarySystemBackground))
            )
    }

    var controlSize: CGFloat { horizontalSizeClass == .regular ? 56 : 48 }

    func controlButton(
        _ key: LocalizedStringKey,
        systemImage: String,
        prominent: Bool = false,
        size: CGSize,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            controlLabel(systemImage: systemImage, prominent: prominent, size: size)
        }
        .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
        .accessibilityLabel(Text(key))
    }

    /// Back and start Page, in the top row opposite the menu. They stay visible,
    /// disabled when there is nowhere to go, so the row does not jump.
    var pageNavigation: some View {
        let showsLabels = horizontalSizeClass == .regular
        return HStack(spacing: 4) {
            Button { Task { await model.navigateBoardBack() } } label: {
                topRowLabel("board_page.back", systemImage: "chevron.backward", showsTitle: showsLabels)
            }
            .disabled(!model.canNavigateBoardBack)
            .accessibilityLabel(Text("board_page.back"))
            Button { Task { await model.navigateBoardHome() } } label: {
                topRowLabel("board_page.start", systemImage: "house", showsTitle: showsLabels)
            }
            .disabled(model.isOnStartPage)
            .accessibilityLabel(Text("board_page.start"))
        }
        .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled)
    }

    func topRowLabel(_ key: LocalizedStringKey, systemImage: String, showsTitle: Bool) -> some View {
        Group {
            if showsTitle {
                Label(key, systemImage: systemImage)
            } else {
                Image(systemName: systemImage)
            }
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(Color.accentColor)
        .padding(.horizontal, 8)
        .frame(minWidth: 44, minHeight: 44)
        .contentShape(Rectangle())
    }

    /// The one corner menu in use mode. It holds everything except the board and
    /// the message bar, so the board can fill the screen.
    func workspaceMenu(boardSetId: String) -> some View {
        Menu {
            Button {
                if model.canEditSelectedBoardSet {
                    Task { await requestEnterEditing(boardSetId: boardSetId) }
                } else if let set = model.selectedBoardSet {
                    Task { await beginScreenLockToggle(set) }
                }
            } label: { Label("board_sets.library.edit", systemImage: "pencil") }
            Button { showHiddenButtons.toggle() } label: {
                Label(
                    LocalizedStringKey(showHiddenButtons ? "board_workspace.hide_hidden" : "board_workspace.show_hidden"),
                    systemImage: showHiddenButtons ? "eye.slash" : "eye"
                )
            }
            Button { exportTargetSetId = boardSetId } label: {
                Label("boardset.export_obz", systemImage: "square.and.arrow.up")
            }
            Button { printScreen(id: boardSetId) } label: {
                Label("boardset.print_pdf", systemImage: "printer")
            }
            .disabled(exportingPdf)
            Divider()
            Button { route = .library } label: {
                Label("board_workspace.exit_library", systemImage: "square.grid.2x2")
            }
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { model.boardModeEnabled = false }
            } label: { Label("toolbar.switch_phrase_mode", systemImage: "text.bubble") }
            Button(action: onShowSettings) {
                Label("toolbar.settings_panel", systemImage: "slider.horizontal.3")
            }
        } label: {
            Label("board_workspace.menu", systemImage: "line.3.horizontal")
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 8)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(Text("board_workspace.menu"))
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanTopBarEnabled)
    }
}
