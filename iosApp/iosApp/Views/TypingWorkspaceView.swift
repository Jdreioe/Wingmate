import SwiftUI
import UIKit
import Shared

/// The Typing workspace:
/// - On compact windows (standard iPhones, closed iPhone Duo outer display, narrow splits),
///   the Message bar stays anchored at the bottom, and the Typing Screen tray and keyboard take turns.
/// - On wide windows (unfolded iPhone Duo inner display in portrait or landscape, iPads),
///   the Message and Typing Screen stay visible together beside a dedicated speech-and-actions panel.
/// - When iPhone Duo is half-open (iOS 27.1+), `ArrangementView` splits the panes across the crease.
struct TypingWorkspaceView: View {
    @ObservedObject var model: IosViewModel
    let recorder: AudioRecorder
    @Binding var editingPhrase: Shared.Phrase?
    @Binding var showAddCategory: Bool
    @Binding var showAddPhrase: Bool
    @Binding var isMessageFullscreen: Bool
    let uiInputFontSize: Double
    let uiTextFieldHeight: Double
    let uiChipFontSize: Double
    let uiPlayIconSize: Double
    /// Full window height measured ignoring the keyboard safe area, supplied by `ContentView`.
    var restingWindowHeight: CGFloat = 0
    let requestMicAndStart: (String) -> Void

    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var messageFocused = false
    @State private var onScreenKeyboard = false
    @State private var page: TypingPage = .all
    @State private var fallbackRestingHeight: CGFloat = 0
    @State private var isWideWindow = false
    @State private var dragStartHeight: CGFloat? = nil
    @State private var pendingMutation: (() -> Void)? = nil
    /// The keyboard's last height, so the tray opens where the keyboard was.
    @AppStorage("typing.keyboardHeight") private var keyboardHeight: Double = 0
    /// A height the Communicator chose by dragging the tray; 0 until then.
    @AppStorage("typing.trayHeight") private var draggedTrayHeight: Double = 0

    private var trayOpen: Bool { !messageFocused }
    /// Landscape with the keyboard up leaves room for one row above the Message bar.
    private var oneRowWhileTyping: Bool { onScreenKeyboard && verticalSizeClass == .compact }

    private var effectiveRestingHeight: CGFloat {
        restingWindowHeight > 0 ? restingWindowHeight : fallbackRestingHeight
    }

    private var minTrayHeight: CGFloat { min(180, maxTrayHeight) }
    private var maxTrayHeight: CGFloat { max(0, effectiveRestingHeight * 0.6) }

    private func trayHeight(bottomSafeInset: CGFloat) -> CGFloat {
        let fromKeyboard = keyboardHeight > 0
            ? CGFloat(keyboardHeight) - bottomSafeInset - 8
            : effectiveRestingHeight * 0.4
        let preferred = draggedTrayHeight > 0 ? CGFloat(draggedTrayHeight) : fromKeyboard
        return min(max(preferred, minTrayHeight), maxTrayHeight)
    }

    private var actionStripActions: [Shared.TypingTrayAction] {
        (model.communication.typingTray?.elements ?? [])
            .filter { $0.kind.name == "ActionStrip" }
            .flatMap { $0.actions }
            .filter { $0.isVisibleForTtsEngineNamed(engineName: model.speech.ttsEngine) }
    }

    private var pagePhrases: [Shared.Phrase] {
        switch page {
        case .category(let id): return model.communication.state.phrases.filter { $0.parentId == id }
        case .all, .history, .likelyNext: return model.communication.state.phrases
        }
    }

    private var inputBinding: Binding<String> {
        Binding(get: { model.communication.input }, set: { model.communication.onInputChanged($0) })
    }

    var body: some View {
        GeometryReader { proxy in
            // Decide wide vs compact from the full window height (ignoring the keyboard inset)
            // so raising the on-screen keyboard on iPad or unfolded Duo never flips layouts.
            let resting = effectiveRestingHeight
            let wide = WindowLayoutClass.usesWideTyping(
                size: proxy.size,
                restingHeight: resting,
                keyboardVisible: onScreenKeyboard || resting > proxy.size.height
            )
            let foldDivision = proxy.activeFoldDivision
            let bottomInset = proxy.safeAreaInsets.bottom

            Group {
                if wide {
                    wideTypingLayout(size: proxy.size, division: foldDivision)
                } else {
                    compactTypingLayout(bottomSafeInset: bottomInset)
                }
            }
            .onChange(of: wide) { _, newWide in
                isWideWindow = newWide
            }
            .onAppear {
                isWideWindow = wide
                if !onScreenKeyboard { fallbackRestingHeight = proxy.size.height }
            }
            .onChange(of: proxy.size.height) { _, h in
                if !onScreenKeyboard { fallbackRestingHeight = h }
            }
        }
        // On compact windows the app bar gives way to the Message while the keyboard is up.
        .toolbar((!isWideWindow && onScreenKeyboard) ? .hidden : .visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                fullscreenButton
                    .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanTopBarEnabled)
            }
        }
        .onAppear {
            // The workspace starts on the Page the Phrase store has selected, so the shown
            // Page and the Category new Phrases go into agree.
            if let id = model.communication.state.selectedCategoryId, model.communication.state.categories.contains(where: { $0.id == id }) {
                page = .category(id)
            } else {
                model.communication.selectCategory(id: nil)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            let height = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect)?.height ?? 0
            // A hardware keyboard shows only a short shortcut bar.
            onScreenKeyboard = height >= 200
            if height >= 200 { keyboardHeight = Double(height) }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            onScreenKeyboard = false
        }
        .sheet(isPresented: Binding(get: { pendingMutation != nil }, set: { if !$0 { pendingMutation = nil } })) {
            EditingAccessUnlockSheet(
                editingAccess: model.editingAccess,
                onUnlocked: {
                    let mutation = pendingMutation
                    pendingMutation = nil
                    mutation?()
                },
                onCancel: { pendingMutation = nil }
            )
        }
        .fullScreenCover(isPresented: $isMessageFullscreen) { fullscreenMessage }
    }

    // MARK: - Compact Layout (Outer Display / Standard iPhone / Narrow Split)

    private func compactTypingLayout(bottomSafeInset: CGFloat) -> some View {
        let currentTrayHeight = trayHeight(bottomSafeInset: bottomSafeInset)
        return VStack(spacing: 8) {
            statusRows
            // Free space above the Message bar; the bar and its input surface stay at the bottom.
            Spacer(minLength: 0)
            if onScreenKeyboard && model.communication.likelyNextAvailable {
                likelyNextRow
            }
            rowsAboveBar
            #if DEBUG
            // Only for a hardware keyboard; the on-screen one brings its own suggestions.
            if messageFocused && !onScreenKeyboard {
                PredictionBar(
                    result: model.communication.predictions,
                    onWordSelected: { model.communication.applyWordPrediction($0) },
                    onLetterSelected: { model.communication.applyLetterPrediction($0) },
                    fontSizeScale: 1.0
                )
            }
            #endif
            TypingMessageBar(
                model: model,
                messageFocused: $messageFocused,
                fontSize: CGFloat(uiInputFontSize),
                minHeight: CGFloat(uiTextFieldHeight),
                iconSize: CGFloat(uiPlayIconSize)
            )
            if trayOpen {
                tray(height: currentTrayHeight, bottomSafeInset: bottomSafeInset)
                    .frame(height: currentTrayHeight)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: trayOpen)
    }

    // MARK: - Wide & Foldable Layout (Unfolded iPhone Duo & iPad)

    @ViewBuilder
    private func wideTypingLayout(size: CGSize, division: FoldDivision?) -> some View {
        let panelWidth = WindowLayoutClass.typingSidePanelWidth(for: size.width)
        let messageHeight: CGFloat = size.height < 420
            ? max(64, CGFloat(uiTextFieldHeight) * 0.9)
            : max(88, CGFloat(uiTextFieldHeight) * 1.25)

        if let division, division.axis == .horizontal {
            // Tabletop / laptop posture on iPhone Duo: keep the Message and primary speech
            // controls above the horizontal crease, and the Typing Screen below it.
            AdaptiveFoldSplitView(splitAxis: .vertical, primaryRatio: 0.42, spacing: 12) {
                VStack(spacing: 8) {
                    statusRows
                    HStack(alignment: .top, spacing: 12) {
                        wideMessageInput(height: messageHeight)
                        wideTopSpeechControls
                            .frame(width: min(220, size.width * 0.3))
                    }
                    if let held = model.communication.heldMessageText {
                        HeldMessageRow(model: model, heldText: held)
                    }
                    #if DEBUG
                    if messageFocused && !onScreenKeyboard {
                        PredictionBar(
                            result: model.communication.predictions,
                            onWordSelected: { model.communication.applyWordPrediction($0) },
                            onLetterSelected: { model.communication.applyLetterPrediction($0) },
                            fontSizeScale: 1.0
                        )
                    }
                    #endif
                    Spacer(minLength: 0)
                }
            } secondary: {
                VStack(spacing: 8) {
                    wideTypingScreenSurface(showActionStrip: true)
                }
            }
        } else if let division, division.axis == .vertical {
            // Book posture on iPhone Duo: ArrangementView aligns primary and secondary
            // cleanly on opposite sides of the vertical fold crease.
            AdaptiveFoldSplitView(splitAxis: .horizontal, primaryRatio: 0.62, spacing: 16) {
                widePrimaryColumn(messageHeight: messageHeight)
            } secondary: {
                wideControlPanel(availableHeight: size.height)
            }
        } else {
            // Flat unfolded inner display (portrait 669×951 or landscape 951×669) and iPad:
            // Message + Typing Screen on the leading side, dedicated control panel on the trailing side.
            HStack(alignment: .top, spacing: 14) {
                widePrimaryColumn(messageHeight: messageHeight)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                wideControlPanel(availableHeight: size.height)
                    .frame(width: panelWidth)
                    .frame(maxHeight: .infinity)
            }
        }
    }

    private func widePrimaryColumn(messageHeight: CGFloat) -> some View {
        VStack(spacing: 10) {
            statusRows
            wideMessageInput(height: messageHeight)
            if onScreenKeyboard && model.communication.likelyNextAvailable {
                likelyNextRow
            }
            #if DEBUG
            if messageFocused && !onScreenKeyboard {
                PredictionBar(
                    result: model.communication.predictions,
                    onWordSelected: { model.communication.applyWordPrediction($0) },
                    onLetterSelected: { model.communication.applyLetterPrediction($0) },
                    fontSizeScale: 1.0
                )
            }
            #endif
            wideTypingScreenSurface(showActionStrip: false)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func wideMessageInput(height: CGFloat) -> some View {
        MultiLineInput(
            text: inputBinding,
            selectedRange: Binding(get: { model.communication.inputSelectionRange }, set: { model.communication.inputSelectionRange = $0 }),
            isFocused: $messageFocused,
            placeholder: NSLocalizedString("tts.placeholder", comment: ""),
            fontSize: CGFloat(uiInputFontSize),
            minHeight: height,
            maxHeight: max(height, CGFloat(uiTextFieldHeight) * 2.2),
            scanEnabled: model.settings.scanningEnabled,
            includeInScanArea: model.settings.scanInputFieldEnabled,
            secondaryLanguage: model.speech.secondaryLanguage,
            secondaryLanguageRanges: model.communication.secondaryLanguageRanges,
            allowsSecondaryLanguageAction: model.communication.hasUsableSecondaryLanguage,
            onMarkSelectionAsSecondaryLanguage: { model.communication.markSelectionAsSecondaryLanguage(range: $0) }
        )
        .animation(.easeInOut(duration: 0.2), value: onScreenKeyboard)
    }

    @ViewBuilder
    private func wideTypingScreenSurface(showActionStrip: Bool) -> some View {
        Group {
            if let typingTray = model.communication.typingTray {
                TypingTrayView(
                    model: model,
                    tray: typingTray,
                    page: $page,
                    recorder: recorder,
                    chipFontSize: CGFloat(uiChipFontSize),
                    requestMutation: requestMutation,
                    onAddPhrase: { showAddPhrase = true },
                    onAddCategory: { showAddCategory = true },
                    onEditPhrase: { editingPhrase = $0 },
                    requestMic: requestMicAndStart,
                    onShowKeyboard: { messageFocused = true },
                    showActionStrip: showActionStrip
                )
            } else if model.communication.typingTrayFailed {
                VStack(spacing: 12) {
                    Text("typing.tray.unavailable").multilineTextAlignment(.center)
                    Button("common.retry") { Task { await model.communication.loadTypingTray() } }
                        .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(.secondarySystemBackground)))
    }

    private var wideTopSpeechControls: some View {
        let paused = model.communication.playback.name == "Paused"
        let playing = model.communication.playback.name == "Playing" || model.communication.playback.name == "Preparing"
        let speechActive = paused || playing
        return VStack(spacing: 8) {
            wideSpeakButton(paused: paused, playing: playing, height: 60)
            HStack(spacing: 8) {
                wideStopButton(enabled: speechActive, height: 48)
                wideKeyboardButton(height: 48)
            }
        }
    }

    private func wideControlPanel(availableHeight: CGFloat) -> some View {
        let paused = model.communication.playback.name == "Paused"
        let playing = model.communication.playback.name == "Playing" || model.communication.playback.name == "Preparing"
        let speechActive = paused || playing
        let roomy = availableHeight >= 460
        let speakHeight: CGFloat = roomy ? min(max(CGFloat(uiPlayIconSize) * 2.0, 68), 92) : 56
        let secondaryHeight: CGFloat = roomy ? 60 : 48
        let panelActions = actionStripActions.filter { action in
            // Speak/Pause/Resume/Stop/Keyboard already have dedicated primary buttons in the panel.
            let single = action.effects.count == 1 ? action.effects.first?.kind.name : nil
            return single != "Keyboard" && single != "Speak" && single != "Pause" && single != "Resume" && single != "Stop"
        }

        return VStack(spacing: 8) {
            wideSpeakButton(paused: paused, playing: playing, height: speakHeight)
            HStack(spacing: 8) {
                wideStopButton(enabled: speechActive, height: secondaryHeight)
                wideKeyboardButton(height: secondaryHeight)
            }
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 8) {
                    if let held = model.communication.heldMessageText {
                        wideHeldMessageCard(heldText: held)
                    }
                    if !panelActions.isEmpty {
                        TypingActionGrid(
                            model: model,
                            actions: panelActions,
                            onShowKeyboard: { messageFocused = true }
                        )
                    }
                }
                .padding(.bottom, 4)
            }
            .scrollDisabled(model.settings.selectOnTouchDown)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color(.secondarySystemBackground)))
    }

    private func wideSpeakButton(paused: Bool, playing: Bool, height: CGFloat) -> some View {
        let targetId = "typing:speak"
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        let action: () -> Void = {
            if paused { model.communication.resumeSpeech() }
            else if playing { model.communication.pauseSpeech() }
            else { model.communication.speakMessage() }
        }
        return Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: min(height * 0.4, 30), weight: .semibold))
                Text(LocalizedStringKey(paused ? "playback.resume" : playing ? "playback.pause" : "playback.play"))
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(shape.fill(Color.accentColor))
            .contentShape(shape)
        }
        .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
        .accessibilityLabel(Text(LocalizedStringKey(paused ? "playback.resume" : playing ? "playback.pause" : "playback.play")))
        .accessibilityHint(Text(LocalizedStringKey(paused || playing ? "" : "accessibility.playback.play_hint")))
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled)
        .onAppear { model.settings.registerAccessTarget(targetId, action: action) }
        .onDisappear { model.settings.unregisterAccessTarget(targetId) }
        .onHover { if $0 { model.settings.accessEnter(targetId) } else { model.settings.accessExit(targetId) } }
        .accessTargetFocus(model: model, targetId: targetId)
    }

    private func wideStopButton(enabled: Bool, height: CGFloat) -> some View {
        let targetId = "typing:stop"
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        return Button(action: model.communication.stopSpeech) {
            VStack(spacing: 4) {
                Image(systemName: "stop.fill")
                    .font(.system(size: 18, weight: .semibold))
                Text("playback.stop")
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(shape.fill(Color(.tertiarySystemFill)))
            .contentShape(shape)
        }
        .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityLabel(Text("playback.stop"))
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled)
        .onAppear { model.settings.registerAccessTarget(targetId, action: model.communication.stopSpeech) }
        .onDisappear { model.settings.unregisterAccessTarget(targetId) }
        .onHover { if $0 { model.settings.accessEnter(targetId) } else { model.settings.accessExit(targetId) } }
        .accessTargetFocus(model: model, targetId: targetId)
    }

    private func wideKeyboardButton(height: CGFloat) -> some View {
        let targetId = "typing:keyboard"
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        let showing = onScreenKeyboard || messageFocused
        let action: () -> Void = { messageFocused.toggle() }
        return Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: showing ? "keyboard.chevron.compact.down" : "keyboard")
                    .font(.system(size: 18, weight: .semibold))
                Text(LocalizedStringKey(showing ? "typing.hide_keyboard" : "typing.show_keyboard"))
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(shape.fill(Color(.tertiarySystemFill)))
            .contentShape(shape)
        }
        .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
        .accessibilityLabel(Text(LocalizedStringKey(showing ? "typing.hide_keyboard" : "typing.show_keyboard")))
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled)
        .onAppear { model.settings.registerAccessTarget(targetId, action: action) }
        .onDisappear { model.settings.unregisterAccessTarget(targetId) }
        .onHover { if $0 { model.settings.accessEnter(targetId) } else { model.settings.accessExit(targetId) } }
        .accessTargetFocus(model: model, targetId: targetId)
    }

    private func wideHeldMessageCard(heldText: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "bookmark.fill")
                    .foregroundStyle(Color.accentColor)
                    .accessibilityHidden(true)
                Text(String(format: NSLocalizedString("typing.held_message", comment: ""), heldText))
                    .font(.subheadline)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button(action: model.communication.toggleHoldThatThought) {
                Label("typing.held_message.swap", systemImage: "arrow.left.arrow.right")
                    .font(.subheadline.weight(.medium))
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(.secondarySystemFill)))
            }
            .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
            .accessibilityHint(Text("accessibility.playback.restore_thought_hint"))
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(.tertiarySystemBackground)))
        .accessibilityElement(children: .contain)
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled)
    }

    // MARK: - Rows

    @ViewBuilder
    private var statusRows: some View {
        if let error = model.communication.state.error {
            HStack(spacing: 8) {
                Text("common.error").bold()
                Text(error).frame(maxWidth: .infinity, alignment: .leading)
                Button("common.retry") { model.communication.retryPhraseLoad() }.buttonStyle(.bordered)
            }
            .foregroundStyle(.red)
        }
        if model.communication.state.isLoading {
            ProgressView().frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private var rowsAboveBar: some View {
        if oneRowWhileTyping {
            TypingActionStrip(
                model: model,
                actions: actionStripActions,
                onShowKeyboard: {},
                leading: AnyView(HStack(spacing: 8) {
                    fullscreenButton
                    if model.communication.hasHeldThought {
                        Button(action: model.communication.toggleHoldThatThought) {
                            Label("typing.held_message.swap", systemImage: "arrow.left.arrow.right")
                                .frame(maxHeight: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(Text(String(format: NSLocalizedString("typing.held_message", comment: ""), model.communication.heldMessageText ?? "")))
                    }
                })
            )
            .frame(height: 56)
            .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled)
        } else {
            if onScreenKeyboard && !actionStripActions.isEmpty {
                TypingActionStrip(
                    model: model,
                    actions: actionStripActions,
                    onShowKeyboard: {},
                    leading: AnyView(fullscreenButton)
                )
                .frame(height: 56)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPlaybackAreaEnabled)
            }
            if let held = model.communication.heldMessageText {
                HeldMessageRow(model: model, heldText: held)
            }
            if messageFocused && !pagePhrases.isEmpty {
                compactPhraseRow
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }

    private var likelyNextRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !oneRowWhileTyping { Text("typing.likely_next").font(.caption).foregroundStyle(.secondary) }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(model.communication.likelyNextPhrases.prefix(4), id: \.id) { phrase in
                        PhraseItemView(model: model, phrase: phrase, recorder: recorder, editable: false,
                            minHeight: oneRowWhileTyping ? 48 : 56, onEdit: {}, requestMic: { _ in }, onDelete: { _ in })
                            .frame(width: 160)
                    }
                }
            }
            .scrollDisabled(model.settings.selectOnTouchDown)
            .frame(height: oneRowWhileTyping ? 56 : 88)
        }
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPhraseGridEnabled)
    }

    private var compactPhraseRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                ForEach(pagePhrases, id: \.id) { phrase in
                    PhraseItemView(
                        model: model,
                        phrase: phrase,
                        recorder: recorder,
                        editable: model.settings.holdToSelectMillis <= 0 && !model.settings.selectOnTouchDown,
                        minHeight: 72,
                        onEdit: { requestMutation { editingPhrase = phrase } },
                        requestMic: { id in requestMutation { requestMicAndStart(id) } },
                        onDelete: { id in requestMutation { model.communication.deletePhrase(id: id) } }
                    )
                    .frame(width: 128)
                }
            }
            .padding(.vertical, 4)
        }
        .scrollDisabled(model.settings.selectOnTouchDown)
        .frame(height: 88)
        .accessibilityHidden(model.settings.scanningEnabled && !model.settings.scanPhraseGridEnabled)
    }

    // MARK: - Tray

    @ViewBuilder
    private func tray(height: CGFloat, bottomSafeInset: CGFloat) -> some View {
        VStack(spacing: 0) {
            trayHandle(currentHeight: height)
            if let typingTray = model.communication.typingTray {
                TypingTrayView(
                    model: model,
                    tray: typingTray,
                    page: $page,
                    recorder: recorder,
                    chipFontSize: CGFloat(uiChipFontSize),
                    requestMutation: requestMutation,
                    onAddPhrase: { showAddPhrase = true },
                    onAddCategory: { showAddCategory = true },
                    onEditPhrase: { editingPhrase = $0 },
                    requestMic: requestMicAndStart,
                    onShowKeyboard: { messageFocused = true }
                )
            } else if model.communication.typingTrayFailed {
                VStack(spacing: 12) {
                    Text("typing.tray.unavailable").multilineTextAlignment(.center)
                    Button("common.retry") { Task { await model.communication.loadTypingTray() } }
                        .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(.secondarySystemBackground)))
    }

    /// Drag to resize the tray; the height is kept for next time.
    private func trayHandle(currentHeight: CGFloat) -> some View {
        Capsule()
            .fill(Color.secondary.opacity(0.5))
            .frame(width: 40, height: 5)
            .frame(maxWidth: .infinity, minHeight: 24)
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { value in
                        let start = dragStartHeight ?? currentHeight
                        dragStartHeight = start
                        draggedTrayHeight = Double(min(max(start - value.translation.height, minTrayHeight), maxTrayHeight))
                    }
                    .onEnded { _ in dragStartHeight = nil }
            )
            .accessibilityElement()
            .accessibilityLabel(Text("typing.tray.resize"))
            .accessibilityValue(Text("\(Int(currentHeight))"))
            .accessibilityAdjustableAction { direction in
                let step: CGFloat = direction == .increment ? 40 : -40
                draggedTrayHeight = Double(min(max(currentHeight + step, minTrayHeight), maxTrayHeight))
            }
    }

    // MARK: - Fullscreen

    private var fullscreenButton: some View {
        Button(action: { isMessageFullscreen = true }) {
            Image(systemName: "arrow.up.left.and.arrow.down.right")
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(Text("playback.fullscreen"))
    }

    private var fullscreenMessage: some View {
        GeometryReader { proxy in
            let messageBody = ScrollView {
                Text(model.communication.input.isEmpty ? NSLocalizedString("tts.placeholder", comment: "") : model.communication.input)
                    .font(.system(size: max(36, CGFloat(uiInputFontSize) * 2.4)))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            let controlsFooter = VStack(spacing: 16) {
                SpeechControls(model: model, buttonSize: 64, speakSize: 88) { model.communication.speakMessage() }
                Button("common.done") { isMessageFullscreen = false }
                    .font(.headline)
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity)

            if let division = proxy.activeFoldDivision, division.axis == .horizontal {
                // Tabletop posture on iPhone Duo: display the large Message on the angled
                // upper half and keep playback controls on the lower half.
                AdaptiveFoldSplitView(splitAxis: .vertical, primaryRatio: 0.6, spacing: 20) {
                    messageBody
                        .padding(24)
                } secondary: {
                    VStack {
                        Spacer(minLength: 0)
                        controlsFooter
                        Spacer(minLength: 0)
                    }
                    .padding(24)
                }
                .background(Color(.systemBackground))
            } else {
                VStack(spacing: 24) {
                    HStack {
                        Spacer()
                        Button("common.done") { isMessageFullscreen = false }
                            .font(.headline)
                    }
                    messageBody
                    SpeechControls(model: model, buttonSize: 64, speakSize: 88) { model.communication.speakMessage() }
                }
                .padding(24)
                .background(Color(.systemBackground))
            }
        }
    }

    // MARK: - Editing access

    /// Runs a vocabulary change now, or after Editing access is unlocked.
    private func requestMutation(_ mutation: @escaping () -> Void) {
        Task {
            if await model.editingAccess.isAuthorized() {
                mutation()
            } else {
                pendingMutation = mutation
            }
        }
    }
}
