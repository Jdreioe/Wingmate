import SwiftUI
import UIKit
import Shared

/// The Typing workspace as a composer (#311, matching Android #299): the Message
/// bar stays anchored at the bottom, and below it the Typing Screen tray and the
/// keyboard take turns. The tray is the resting surface; focusing the Message
/// means typing. While the keyboard is up, a row of Phrases and the Action strip
/// sit above the bar instead.
struct TypingWorkspaceView: View {
    @ObservedObject var model: IosViewModel
    let recorder: AudioRecorder
    @Binding var editingPhrase: Shared.Phrase?
    @Binding var showAddCategory: Bool
    @Binding var showAddPhrase: Bool
    let uiInputFontSize: Double
    let uiTextFieldHeight: Double
    let uiChipFontSize: Double
    let uiPlayIconSize: Double
    let requestMicAndStart: (String) -> Void

    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var messageFocused = false
    @State private var onScreenKeyboard = false
    @State private var page: TypingPage = .all
    @State private var isMessageFullscreen = false
    @State private var restingHeight: CGFloat = 0
    @State private var dragStartHeight: CGFloat? = nil
    @State private var pendingMutation: (() -> Void)? = nil
    /// The keyboard's last height, so the tray opens where the keyboard was.
    @AppStorage("typing.keyboardHeight") private var keyboardHeight: Double = 0
    /// A height the Communicator chose by dragging the tray; 0 until then.
    @AppStorage("typing.trayHeight") private var draggedTrayHeight: Double = 0

    private var trayOpen: Bool { !messageFocused }
    /// Landscape with the keyboard up leaves room for one row above the Message bar.
    private var oneRowWhileTyping: Bool { onScreenKeyboard && verticalSizeClass == .compact }

    private var minTrayHeight: CGFloat { min(200, maxTrayHeight) }
    private var maxTrayHeight: CGFloat { max(0, restingHeight * 0.6) }

    private var trayHeight: CGFloat {
        let fromKeyboard = keyboardHeight > 0
            ? CGFloat(keyboardHeight) - Self.windowBottomInset - 8
            : restingHeight * 0.4
        let preferred = draggedTrayHeight > 0 ? CGFloat(draggedTrayHeight) : fromKeyboard
        return min(max(preferred, minTrayHeight), maxTrayHeight)
    }

    private var actionStripActions: [Shared.TypingTrayAction] {
        (model.typingTray?.elements ?? [])
            .filter { $0.kind == .actionStrip }
            .flatMap { $0.actions }
    }

    private var pagePhrases: [Shared.Phrase] {
        switch page {
        case .category(let id): return model.state.phrases.filter { $0.parentId == id }
        case .all, .history: return model.state.phrases
        }
    }

    var body: some View {
        GeometryReader { proxy in
            VStack(spacing: 8) {
                statusRows
                // Free space above the Message bar; the bar and its input surface stay at the bottom.
                Spacer(minLength: 0)
                rowsAboveBar
                #if DEBUG
                // Only for a hardware keyboard; the on-screen one brings its own suggestions.
                if messageFocused && !onScreenKeyboard {
                    PredictionBar(
                        result: model.predictions,
                        onWordSelected: { model.applyWordPrediction($0) },
                        onLetterSelected: { model.applyLetterPrediction($0) },
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
                    tray
                        .frame(height: trayHeight)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: trayOpen)
            .onAppear { if !onScreenKeyboard { restingHeight = proxy.size.height } }
            .onChange(of: proxy.size.height) { _, height in
                if !onScreenKeyboard { restingHeight = height }
            }
        }
        // The app bar gives way to the Message while the keyboard is up.
        .toolbar(onScreenKeyboard ? .hidden : .visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { fullscreenButton }
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
                model: model,
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

    // MARK: - Rows

    @ViewBuilder
    private var statusRows: some View {
        if let error = model.state.error {
            HStack(spacing: 8) {
                Text("common.error").bold()
                Text(error).frame(maxWidth: .infinity, alignment: .leading)
                Button("common.retry") { model.retryPhraseLoad() }.buttonStyle(.bordered)
            }
            .foregroundStyle(.red)
        }
        if model.state.isLoading {
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
                    if model.hasHeldThought {
                        Button(action: model.toggleHoldThatThought) {
                            Label("typing.held_message.swap", systemImage: "arrow.left.arrow.right")
                                .frame(maxHeight: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(Text(String(format: NSLocalizedString("typing.held_message", comment: ""), model.heldMessageText ?? "")))
                    }
                })
            )
            .frame(height: 56)
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
            }
            if let held = model.heldMessageText {
                HeldMessageRow(model: model, heldText: held)
            }
            if messageFocused && !pagePhrases.isEmpty {
                compactPhraseRow
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }

    private var compactPhraseRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                ForEach(pagePhrases, id: \.id) { phrase in
                    PhraseItemView(
                        model: model,
                        phrase: phrase,
                        recorder: recorder,
                        editable: model.holdToSelectMillis <= 0,
                        minHeight: 72,
                        onEdit: { requestMutation { editingPhrase = phrase } },
                        requestMic: { id in requestMutation { requestMicAndStart(id) } },
                        onDelete: { id in requestMutation { model.deletePhrase(id: id) } }
                    )
                    .frame(width: 128)
                }
            }
            .padding(.vertical, 4)
        }
        .frame(height: 88)
        .accessibilityHidden(model.scanningEnabled && !model.scanPhraseGridEnabled)
    }

    // MARK: - Tray

    @ViewBuilder
    private var tray: some View {
        VStack(spacing: 0) {
            trayHandle
            if let typingTray = model.typingTray {
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
            } else if model.typingTrayFailed {
                VStack(spacing: 12) {
                    Text("typing.tray.unavailable").multilineTextAlignment(.center)
                    Button("common.retry") { Task { await model.loadTypingTray() } }
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
    private var trayHandle: some View {
        Capsule()
            .fill(Color.secondary.opacity(0.5))
            .frame(width: 40, height: 5)
            .frame(maxWidth: .infinity, minHeight: 24)
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { value in
                        let start = dragStartHeight ?? trayHeight
                        dragStartHeight = start
                        draggedTrayHeight = Double(min(max(start - value.translation.height, minTrayHeight), maxTrayHeight))
                    }
                    .onEnded { _ in dragStartHeight = nil }
            )
            .accessibilityElement()
            .accessibilityLabel(Text("typing.tray.resize"))
            .accessibilityValue(Text("\(Int(trayHeight))"))
            .accessibilityAdjustableAction { direction in
                let step: CGFloat = direction == .increment ? 40 : -40
                draggedTrayHeight = Double(min(max(trayHeight + step, minTrayHeight), maxTrayHeight))
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
        VStack(spacing: 24) {
            HStack {
                Spacer()
                Button("common.done") { isMessageFullscreen = false }
                    .font(.headline)
            }
            ScrollView {
                Text(model.input.isEmpty ? NSLocalizedString("tts.placeholder", comment: "") : model.input)
                    .font(.system(size: max(36, CGFloat(uiInputFontSize) * 2.4)))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            SpeechControls(model: model, buttonSize: 64, speakSize: 88) { model.speakMessage() }
        }
        .padding(24)
        .background(Color(.systemBackground))
    }

    // MARK: - Editing access

    /// Runs a vocabulary change now, or after Editing access is unlocked.
    private func requestMutation(_ mutation: @escaping () -> Void) {
        Task {
            if await model.editingIsAuthorized() {
                mutation()
            } else {
                pendingMutation = mutation
            }
        }
    }

    private static var windowBottomInset: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?.safeAreaInsets.bottom ?? 0
    }
}
