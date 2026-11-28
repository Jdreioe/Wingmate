import SwiftUI
import UIKit

/// Search sheet for locating any word across the active Screen's Pages and starting
/// step-by-step guided navigation without modifying vocabulary (#408).
struct WordFinderSheet: View {
    @ObservedObject var screens: ScreensModel
    let includeHidden: Bool
    let onSelect: (WordFinderMatchInfo) -> Void
    let onDismiss: () -> Void

    @State private var query: String = ""
    @State private var results: [WordFinderMatchInfo] = []
    @State private var isSearching = false
    @FocusState private var searchFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    TextField("word_finder.search_hint", text: $query)
                        .textFieldStyle(.plain)
                        .focused($searchFocused)
                        .submitLabel(.search)
                        .onSubmit {
                            if let first = results.first {
                                onSelect(first)
                            }
                        }
                    if !query.isEmpty {
                        Button {
                            query = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                                .frame(minWidth: 32, minHeight: 32)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("word_finder.clear_search"))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.horizontal)
                .padding(.vertical, 8)

                Divider()

                contentView
            }
            .navigationTitle("word_finder.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.close") {
                        onDismiss()
                    }
                }
            }
            .onAppear {
                searchFocused = true
            }
            .task(id: query) {
                let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty {
                    results = []
                    isSearching = false
                    return
                }
                isSearching = true
                let found = await screens.findWords(query: trimmed, includeHidden: includeHidden)
                guard !Task.isCancelled else { return }
                results = found
                isSearching = false
            }
        }
    }

    @ViewBuilder
    private var contentView: some View {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "magnifyingglass")
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Text("word_finder.prompt")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(24)
        } else if isSearching && results.isEmpty {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if results.isEmpty {
            VStack(spacing: 12) {
                Text("word_finder.no_results")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(24)
        } else {
            List(results) { match in
                Button {
                    onSelect(match)
                } label: {
                    WordFinderResultRow(match: match)
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
        }
    }
}

private struct WordFinderResultRow: View {
    let match: WordFinderMatchInfo

    private var showHiddenBadge: Bool {
        match.isHidden || match.hasHiddenStep
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 8) {
                Text(match.label)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if showHiddenBadge {
                    WordFinderBadgeView(
                        titleKey: "word_finder.hidden_badge",
                        systemImage: "eye.slash"
                    )
                }
                if match.startsFromRoot {
                    WordFinderBadgeView(
                        titleKey: "word_finder.from_start_page",
                        systemImage: "house"
                    )
                }
            }
            if let vocalization = match.vocalization, !vocalization.isEmpty {
                Text(vocalization)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Text(match.formattedPath)
                .font(.subheadline)
                .foregroundStyle(Color.accentColor)
                .lineLimit(2)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

private struct WordFinderBadgeView: View {
    let titleKey: LocalizedStringKey
    let systemImage: String

    var body: some View {
        Label(titleKey, systemImage: systemImage)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color(.secondarySystemBackground), in: Capsule())
    }
}

/// Compact banner shown above the Page grid while step-by-step Word Finder guidance is active.
/// Respects touch, touch-down, switch scanning, dwell, and VoiceOver.
struct WordFinderGuidanceBanner: View {
    @ObservedObject var model: IosViewModel
    let guidance: WordFinderGuidanceState
    let onGoToStep: (String) -> Void
    let onStop: () -> Void

    private var step: WordFinderStepInfo? { guidance.currentStep }

    private var stepInstructionText: String {
        guard let step else { return "" }
        return String(
            format: NSLocalizedString("word_finder.step_instruction", comment: ""),
            guidance.currentStepIndex + 1,
            guidance.totalSteps,
            step.buttonLabel
        )
    }

    var body: some View {
        if let step {
            let isOnStepPage = model.screens.selectedBoardId == step.boardId
            let findingTitle = String(format: NSLocalizedString("word_finder.finding", comment: ""), guidance.match.label)
            HStack(spacing: 10) {
                Image(systemName: "safari.fill")
                    .font(.title3)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text("\(findingTitle) · \(guidance.match.formattedPath)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(stepInstructionText)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)

                if !isOnStepPage {
                    let goTargetId = "screens:word-finder-go-to-step"
                    let goAction: () -> Void = {
                        if let boardId = model.screens.wordFinderGuidance?.currentStep?.boardId {
                            onGoToStep(boardId)
                        }
                    }
                    Button(action: goAction) {
                        Label("word_finder.go_to_step", systemImage: "arrow.right")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 10)
                            .frame(minHeight: 44)
                            .background(Color.accentColor.opacity(0.16), in: Capsule())
                    }
                    .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
                    .onAppear { model.settings.registerAccessTarget(goTargetId, action: goAction) }
                    .onDisappear { model.settings.unregisterAccessTarget(goTargetId) }
                    .onHover { if $0 { model.settings.accessEnter(goTargetId) } else { model.settings.accessExit(goTargetId) } }
                    .accessTargetFocus(model: model, targetId: goTargetId)
                }

                let stopTargetId = "screens:word-finder-stop"
                Button(action: onStop) {
                    Label("word_finder.stop", systemImage: "xmark")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 10)
                        .frame(minHeight: 44)
                        .background(Color(.tertiarySystemBackground), in: Capsule())
                }
                .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown))
                .accessibilityLabel(Text("word_finder.stop_description"))
                .onAppear { model.settings.registerAccessTarget(stopTargetId, action: onStop) }
                .onDisappear { model.settings.unregisterAccessTarget(stopTargetId) }
                .onHover { if $0 { model.settings.accessEnter(stopTargetId) } else { model.settings.accessExit(stopTargetId) } }
                .accessTargetFocus(model: model, targetId: stopTargetId)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.accentColor.opacity(model.settings.highContrastMode ? 0.25 : 0.12))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Color.accentColor, lineWidth: model.settings.highContrastMode ? 2 : 1)
            )
            .padding(.horizontal)
            .onChange(of: stepInstructionText) { _, instruction in
                if !instruction.isEmpty {
                    UIAccessibility.post(notification: .announcement, argument: instruction)
                }
            }
            .onAppear {
                if !stepInstructionText.isEmpty {
                    UIAccessibility.post(notification: .announcement, argument: stepInstructionText)
                }
            }
        }
    }
}
