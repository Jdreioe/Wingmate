import SwiftUI
import Shared
import AudioToolbox

extension SymbolBoardWorkspaceView {
    @ViewBuilder
    func boardPreview(isEditMode: Bool) -> some View {
        if let board = model.screens.selectedBoard, model.screens.boardCellsReady(for: board.id) {
            let rows = max(1, Int(board.grid?.rows ?? 1))
            let cols = max(1, Int(board.grid?.columns ?? 1))
            let desiredGap = Double(model.screens.resolvedBoardCellGapDp ?? 8)
            let desiredInset = Double(model.screens.resolvedBoardOuterMarginDp ?? 8)
            let hasVisibleButton = model.screens.boardCells.contains {
                model.screens.boardButtonIsVisible(
                    hidden: $0.hidden,
                    isEditMode: isEditMode,
                    showHiddenButtons: showHiddenButtons
                )
            }
            let touchExitActive = hasVisibleButton &&
                TouchExitControllerKt.touchExitIsActive(
                    isEditMode: isEditMode,
                    holdToSelectMillis: Int64(model.settings.holdToSelectMillis),
                    selectOnTouchDown: model.settings.selectOnTouchDown,
                    selectOnTouchExit: model.settings.selectOnTouchExit
                )

            GeometryReader { geometry in
                let isCompact = board.compactGrid
                let rawGridHeightFraction = board.gridHeightFraction?.floatValue
                let hasSavedFraction = (rawGridHeightFraction?.isFinite == true) && (rawGridHeightFraction ?? 0) > 0
                let prelimInset = min(
                    CGFloat(max(0, desiredInset)),
                    max(0, (geometry.size.width - CGFloat(BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP) * CGFloat(cols)) / 2),
                    max(0, (geometry.size.height - CGFloat(BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP) * CGFloat(rows)) / 2)
                )
                let prelimAvailableHeight = max(0, geometry.size.height - (prelimInset * 2))
                // Mirror Compose sizing: non-compact boards fill the full available
                // height (fraction 1), compact/keyboard boards size to their content
                // (fraction computed from a minimum cell height of 72pt).
                let minCellHeight: CGFloat = 72
                let minContentHeight = (minCellHeight * CGFloat(rows)) + (CGFloat(max(0, desiredGap)) * CGFloat(rows - 1))
                let defaultFraction: CGFloat = isCompact
                    ? min(max(minContentHeight / max(minContentHeight, prelimAvailableHeight), 0.15), 1)
                    : 1
                let gridHeightFraction: CGFloat = hasSavedFraction
                    ? CGFloat(rawGridHeightFraction ?? 1)
                    : defaultFraction
                let fraction = min(max(gridHeightFraction, 0.15), 1)

                let effectiveSpacing = BoardSettingsKt.effectiveKeyguardSpacing(
                    viewportWidthDp: Double(geometry.size.width),
                    viewportHeightDp: Double(geometry.size.height),
                    columns: Int32(cols),
                    rows: Int32(rows),
                    desiredHorizontalGapDp: desiredGap,
                    desiredVerticalGapDp: desiredGap,
                    desiredOuterMarginDp: desiredInset,
                    minimumCellSizeDp: BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP,
                    heightFraction: Double(fraction),
                    constrainHeight: true
                )
                let gridGap = CGFloat(min(effectiveSpacing.horizontalGapDp, effectiveSpacing.verticalGapDp))
                let gridInset = CGFloat(effectiveSpacing.outerMarginDp)
                let effectiveFraction = CGFloat(effectiveSpacing.effectiveHeightFraction)
                let availableHeight = max(0, geometry.size.height - (gridInset * 2))
                let availableWidth = max(0, geometry.size.width - (gridInset * 2))

                let contentHeight = max(0, availableHeight * effectiveFraction)
                let contentWidth = max(0, availableWidth)
                let foldLayout = BoardFoldLayout.compute(
                    rows: rows,
                    columns: cols,
                    contentWidth: contentWidth,
                    contentHeight: contentHeight,
                    gap: gridGap,
                    gridInset: gridInset,
                    division: geometry.activeFoldDivision
                )
                let gridMetrics = BoardGridMetrics(
                    layout: foldLayout,
                    rows: rows,
                    columns: cols
                )

                ScrollView(.vertical, showsIndicators: false) {
                    ZStack(alignment: .topLeading) {
                        ForEach(model.screens.boardFieldItems) { field in
                            let occupied = model.screens.cellAt(row: field.row, col: field.column) != nil
                            let rect = foldLayout.frame(
                                row: field.row,
                                column: field.column,
                                rowSpan: field.rowSpan,
                                columnSpan: field.columnSpan
                            )
                            // Use mode leaves empty cells blank. They are not buttons.
                            if occupied || isEditMode {
                                boardCellButton(
                                    pageId: board.id,
                                    row: field.row,
                                    col: field.column,
                                    isEditMode: isEditMode,
                                    width: rect.width,
                                    height: rect.height,
                                    rowSpan: field.rowSpan,
                                    columnSpan: field.columnSpan,
                                    metrics: gridMetrics
                                )
                                .offset(fieldDragOffset(for: field.buttonId, fallback: CGSize(width: rect.minX, height: rect.minY)))
                            } else {
                                Color.clear
                                    .frame(width: rect.width, height: rect.height)
                                    .offset(x: rect.minX, y: rect.minY)
                                    .accessibilityHidden(true)
                                    .allowsHitTesting(false)
                            }
                        }
                        if isEditMode {
                            fieldEditChrome(
                                layout: foldLayout,
                                contentWidth: contentWidth,
                                contentHeight: contentHeight,
                                rows: rows,
                                columns: cols
                            )
                        }
                    }
                    .coordinateSpace(name: "boardGrid")
                    .frame(width: contentWidth, height: contentHeight, alignment: .topLeading)
                    .contentShape(Rectangle())
                    .highPriorityGesture(
                        touchExitGesture(
                            pageId: board.id,
                            layout: foldLayout,
                            layoutRevision: model.screens.boardLayoutRevision,
                            isEditMode: isEditMode
                        ),
                        including: touchExitActive ? .gesture : .subviews
                    )
                    .padding(gridInset)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background {
                    if !model.settings.highContrastMode { PageBackgroundView(source: model.screens.boardBackgroundImageUrl, fit: model.screens.boardBackgroundImageFit) }
                }
                // Hit boxes follow the computed layout (rows, columns, gap, inset, height fraction,
                // viewport, fold). Any change invalidates a held finger.
                .onChange(of: foldLayout) { _, _ in
                    cancelTouchExit()
                }
                .onChange(of: gridInset) { _, _ in
                    cancelTouchExit()
                }
                .background(
                    model.settings.highContrastMode
                        ? Color(.systemBackground)
                        : colorFromHex(board.backgroundColor, fallback: Color(.secondarySystemBackground))
                )
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .scrollDisabled((model.settings.selectOnTouchDown || touchExitActive) && !isEditMode)
                .simultaneousGesture(DragGesture(minimumDistance: 24).onEnded { value in
                    guard !isEditMode, abs(value.translation.width) >= 64,
                          abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                    Task {
                        await model.screens.swipePage(
                            by: value.translation.width < 0 ? 1 : -1,
                            touchExitOwnsGrid: touchExitActive
                        )
                    }
                })
                .onChange(of: touchExitGestureActive) { _, active in
                    if !active {
                        cancelTouchExit(latchUntilLift: false)
                        _ = touchExitLatch.lift()
                    }
                }
                .onChange(of: touchExitActive) { _, active in
                    if !active { cancelTouchExit() }
                }
                .onChange(of: board.id) { _, _ in
                    cancelTouchExit()
                }
                .onChange(of: model.screens.selectedBoardId) { _, _ in
                    cancelTouchExit()
                }
                .onChange(of: model.screens.loadedBoardCellsPageId) { _, _ in
                    if !model.screens.boardCellsReady(for: board.id) { cancelTouchExit() }
                }
                .onChange(of: model.screens.boardFieldItems) { _, _ in
                    cancelTouchExit()
                }
                .onChange(of: model.screens.boardCells) { _, _ in
                    cancelTouchExit()
                }
                // A grid that mounts or unmounts cannot own a held finger: its gesture (and the
                // observers that would clear the latch on lift) belong to that grid instance.
                .onAppear {
                    touchExitLatch.reset()
                }
                .onDisappear {
                    cancelTouchExit(latchUntilLift: false)
                    touchExitLatch.reset()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .id(board.id)
            .transition(.opacity)
        } else if let pageId = model.screens.selectedBoardId, model.screens.boardCellsLoadFailedPageId == pageId {
            VStack(spacing: 12) {
                Text(NSLocalizedString("board_sets.error.load_failed", comment: ""))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button(NSLocalizedString("common.retry", comment: "")) {
                    Task { await model.screens.loadSelectedBoard() }
                }
                .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(24)
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 24)
        }
    }

    func boardCellButton(pageId: String, row: Int, col: Int, isEditMode: Bool, width: CGFloat, height: CGFloat, rowSpan: Int = 1, columnSpan: Int = 1, metrics: BoardGridMetrics = BoardGridMetrics()) -> some View {
        let cell = model.screens.cellAt(row: row, col: col)
        let cornerRadius = boardCellCornerRadius(cell, width: width, height: height)
        let isLinked = trimmed(cell?.linkedBoardId) != nil
        let sourceCellId = "\(row):\(col)"
        let anchor = BoardCellAnchor(row: row, column: col)
        let isSelected = isEditMode && selectedFieldAnchor == anchor && cell != nil
        let isGuided = cell != nil && model.screens.guidedWordFinderButtonId == cell?.buttonId
        let guidedHint: String = {
            guard isGuided, let guidance = model.screens.wordFinderGuidance else { return "" }
            if guidance.isOnFinalStep {
                return NSLocalizedString("word_finder.target_description", comment: "")
            }
            return String(
                format: NSLocalizedString("word_finder.next_step_description", comment: ""),
                guidance.match.label
            )
        }()
        let combinedAccessibilityHint: String = {
            let base = cell == nil ? "" : boardCellAccessibilityHint(cell)
            if guidedHint.isEmpty { return base }
            if base.isEmpty { return guidedHint }
            return "\(guidedHint). \(base)"
        }()
        let hiddenInRunMode = !model.screens.boardButtonIsVisible(
            hidden: cell?.hidden == true,
            isEditMode: isEditMode,
            showHiddenButtons: showHiddenButtons
        )

        let touchExitActive = TouchExitControllerKt.touchExitIsActive(
            isEditMode: isEditMode,
            holdToSelectMillis: Int64(model.settings.holdToSelectMillis),
            selectOnTouchDown: model.settings.selectOnTouchDown,
            selectOnTouchExit: model.settings.selectOnTouchExit
        )
        return ZStack(alignment: .topTrailing) {
            Button {
                if suppressNextCellTap {
                    suppressNextCellTap = false
                    return
                }
                if isEditMode && model.screens.canEditSelectedBoardSet {
                    if let cell, isGuided, let guidance = model.screens.wordFinderGuidance {
                        if !guidance.isOnFinalStep, let nextBoardId = guidance.currentStep?.targetBoardId ?? trimmed(cell.linkedBoardId) {
                            model.screens.advanceWordFinderGuidanceIfMatching(boardId: pageId, buttonId: cell.buttonId)
                            selectedFieldAnchor = nil
                            Task { await model.screens.selectBoard(id: nextBoardId, resetStack: true) }
                            return
                        }
                        model.screens.advanceWordFinderGuidanceIfMatching(boardId: pageId, buttonId: cell.buttonId)
                    }
                    if cell == nil {
                        openCellEditor(row: row, col: col, existing: nil)
                    } else if selectedFieldAnchor == anchor {
                        openCellEditor(row: row, col: col, existing: cell)
                    } else {
                        selectField(anchor)
                    }
                } else {
                    guard model.settings.holdToSelectMillis <= 0, !touchExitActive else { return }
                    activateBoardCell(cell, pageId: pageId, row: row, col: col, sourceCellId: sourceCellId)
                }
            } label: {
                // One view in both states, so starting the fly-in does not rebuild
                // the cell (and reload its symbol image).
                let flyingTokenId = activeSentenceAnimation.flatMap { $0.sourceCellId == sourceCellId ? $0.tokenId : nil }
                boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked, isEditMode: isEditMode, isGuided: isGuided, width: width, height: height, rowSpan: rowSpan, columnSpan: columnSpan)
                    .matchedGeometryEffect(id: flyingTokenId ?? "cell-\(sourceCellId)", in: sentenceAnimationNamespace, isSource: true)
                    .zIndex(flyingTokenId == nil ? 0 : 3)
            }
            .buttonStyle(CommunicationButtonStyle(touchDown: model.settings.selectOnTouchDown && model.settings.holdToSelectMillis <= 0 && !isEditMode))
            .frame(width: width, height: height)
            .simultaneousGesture(
                LongPressGesture(minimumDuration: max(0.01, model.settings.holdToSelectMillis / 1_000))
                    .onEnded { _ in
                        guard !isEditMode, model.settings.holdToSelectMillis > 0 else { return }
                        activateBoardCell(cell, pageId: pageId, row: row, col: col, sourceCellId: sourceCellId)
                    }
            )
            .onHover { hovering in
                let targetId = model.screens.screenButtonAccessTargetId(pageId: pageId, buttonId: cell?.buttonId ?? sourceCellId)
                if hovering {
                    model.settings.accessEnter(targetId)
                    if model.settings.auditoryFishingEnabled, !model.settings.inputIsPaused {
                        if let preview = boardCellPreviewText(cell) {
                            model.communication.previewSpeech(preview, languageTag: cell?.language)
                        } else {
                            model.communication.cancelPreviewSpeech()
                        }
                    }
                } else {
                    model.settings.accessExit(targetId)
                }
            }
            .onAppear {
                if !isEditMode, let buttonId = cell?.buttonId {
                    model.screens.registerScreenButtonAccessTarget(
                        pageId: pageId,
                        row: row,
                        col: col,
                        buttonId: buttonId
                    ) { currentCell in
                        activateBoardCell(currentCell, pageId: pageId, row: row, col: col, sourceCellId: sourceCellId)
                    }
                }
            }
            .onChange(of: cell?.buttonId) { oldButtonId, newButtonId in
                if let oldButtonId {
                    model.settings.unregisterAccessTarget(model.screens.screenButtonAccessTargetId(pageId: pageId, buttonId: oldButtonId))
                }
                if !isEditMode, let newButtonId {
                    model.screens.registerScreenButtonAccessTarget(
                        pageId: pageId,
                        row: row,
                        col: col,
                        buttonId: newButtonId
                    ) { currentCell in
                        activateBoardCell(currentCell, pageId: pageId, row: row, col: col, sourceCellId: sourceCellId)
                    }
                }
            }
            .onChange(of: isEditMode) { _, editing in
                if let buttonId = cell?.buttonId {
                    if editing {
                        model.settings.unregisterAccessTarget(model.screens.screenButtonAccessTargetId(pageId: pageId, buttonId: buttonId))
                    } else {
                        model.screens.registerScreenButtonAccessTarget(
                            pageId: pageId,
                            row: row,
                            col: col,
                            buttonId: buttonId
                        ) { currentCell in
                            activateBoardCell(currentCell, pageId: pageId, row: row, col: col, sourceCellId: sourceCellId)
                        }
                    }
                }
            }
            .onDisappear {
                model.settings.unregisterAccessTarget(
                    model.screens.screenButtonAccessTargetId(pageId: pageId, buttonId: cell?.buttonId ?? sourceCellId)
                )
            }
            .accessTargetFocus(
                model: model,
                targetId: model.screens.screenButtonAccessTargetId(pageId: pageId, buttonId: cell?.buttonId ?? sourceCellId)
            )
            .overlay {
                let targetId = model.screens.screenButtonAccessTargetId(pageId: pageId, buttonId: cell?.buttonId ?? sourceCellId)
                if !isEditMode && model.settings.accessTargetId == targetId && model.settings.pointerEmphasisStyle != "System" {
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .stroke(Color.accentColor, lineWidth: 3 * model.settings.pointerEmphasisScale)
                        .padding(6 / model.settings.pointerEmphasisScale)
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }
                if !isEditMode && model.settings.accessTargetId == targetId && model.settings.accessDwellProgress > 0 {
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .trim(from: 0, to: model.settings.accessDwellProgress)
                        .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }
            }
            .accessibilityLabel(Text(cell == nil
                ? NSLocalizedString("boardset.cell.add", comment: "")
                : boardCellDisplayLabel(cell)))
            .accessibilityHint(Text(combinedAccessibilityHint))
            .accessibilitySortPriority(Double(max(0, 10_000 - (row * 100 + col))))
            .accessibilityAddTraits(isSelected ? .isSelected : [])
            .accessibilityAction(.default) {
                if isEditMode && model.screens.canEditSelectedBoardSet {
                    if let cell, isGuided, let guidance = model.screens.wordFinderGuidance {
                        if !guidance.isOnFinalStep, let nextBoardId = guidance.currentStep?.targetBoardId ?? trimmed(cell.linkedBoardId) {
                            model.screens.advanceWordFinderGuidanceIfMatching(boardId: pageId, buttonId: cell.buttonId)
                            selectedFieldAnchor = nil
                            Task { await model.screens.selectBoard(id: nextBoardId, resetStack: true) }
                            return
                        }
                        model.screens.advanceWordFinderGuidanceIfMatching(boardId: pageId, buttonId: cell.buttonId)
                    }
                    openCellEditor(row: row, col: col, existing: cell)
                } else if cell != nil {
                    activateBoardCell(cell, pageId: pageId, row: row, col: col, sourceCellId: sourceCellId)
                }
            }
            .modifier(FieldEditActions(
                enabled: isEditMode && cell != nil && model.screens.canEditSelectedBoardSet,
                moveUp: { Task { await moveField(from: anchor, directionRow: -1, directionColumn: 0) } },
                moveDown: { Task { await moveField(from: anchor, directionRow: 1, directionColumn: 0) } },
                moveLeft: { Task { await moveField(from: anchor, directionRow: 0, directionColumn: -1) } },
                moveRight: { Task { await moveField(from: anchor, directionRow: 0, directionColumn: 1) } },
                wider: { Task { await resizeField(anchor, deltaRows: 0, deltaColumns: 1) } },
                narrower: { Task { await resizeField(anchor, deltaRows: 0, deltaColumns: -1) } },
                taller: { Task { await resizeField(anchor, deltaRows: 1, deltaColumns: 0) } },
                shorter: { Task { await resizeField(anchor, deltaRows: -1, deltaColumns: 0) } }
            ))
            .simultaneousGesture(editMoveGesture(anchor: anchor, cell: cell, isEditMode: isEditMode, metrics: metrics))

            if isSelected, model.screens.canEditSelectedBoardSet {
                Button(role: .destructive) {
                    selectedFieldAnchor = nil
                    Task { await model.screens.clearSelectedBoardCell(row: row, col: col) }
                } label: {
                    Image(systemName: "trash.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.red)
                        .background(Color(.systemBackground), in: Circle())
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("boardset.cell.clear"))
            }
            if cell?.hidden == true && !isEditMode && showHiddenButtons {
                Image(systemName: "eye.fill")
                    .foregroundStyle(.blue)
                    .padding(6)
                    .accessibilityLabel(Text("board_workspace.temporarily_revealed"))
            }
            if !isEditMode, isLinked, let linkedBoardId = trimmed(cell?.linkedBoardId) {
                let isHomeLink = linkedBoardId == model.screens.selectedBoardSet?.rootBoardId
                Image(systemName: isHomeLink ? "house.fill" : "arrowshape.turn.up.right.fill")
                    .font(.caption)
                    .foregroundStyle(Color.primary.opacity(0.8))
                    .padding(4)
                    .background(.regularMaterial, in: Circle())
                    .overlay(Circle().stroke(Color(.separator), lineWidth: 1))
                    .accessibilityLabel(Text(isHomeLink ? NSLocalizedString("boardset.cell.action.home", comment: "") : String(format: NSLocalizedString("board_cell_opens_board", comment: ""), model.screens.boardDisplayName(id: linkedBoardId))))
            }
        }
        .opacity(hiddenInRunMode ? 0 : (cell?.hidden == true && isEditMode ? 0.5 : 1))
        .allowsHitTesting(!hiddenInRunMode)
        .accessibilityHidden(hiddenInRunMode || (model.settings.scanningEnabled && !model.settings.scanPhraseGridEnabled))
    }

    func boardCellContent(row: Int, col: Int, cell: BoardCellInfo?, isLinked: Bool, isEditMode: Bool, isGuided: Bool = false, width: CGFloat, height: CGFloat, rowSpan: Int = 1, columnSpan: Int = 1) -> some View {
        // Labels follow the height of one grid cell, so they grow with the grid
        // instead of staying at a fixed point size; merged fields scale up further.
        let unitHeight = height / CGFloat(max(1, rowSpan))
        let labelSize = min(max(unitHeight * 0.2, 15), 40) * model.screens.boardFieldFontScale(rowSpan: rowSpan, columnSpan: columnSpan)
        if cell == nil && isEditMode {
            return AnyView(
                ZStack {
                    RoundedRectangle(cornerRadius: 0)
                        .fill(Color.clear)
                        .overlay(
                            RoundedRectangle(cornerRadius: 0)
                                .strokeBorder(Color(.separator), style: StrokeStyle(lineWidth: 1, dash: [4]))
                        )
                    Image(systemName: "plus")
                        .font(.largeTitle)
                        .foregroundStyle(Color.accentColor)
                        .accessibilityHidden(true)
                }
                .frame(width: width, height: height)
            )
        }
        let isHighlighted = model.settings.highlightedButtonId != nil && model.settings.highlightedButtonId == cell?.buttonId
        let hasSymbol = model.screens.boardShowSymbols && trimmed(cell?.imageUrl) != nil
        return AnyView(
            VStack(alignment: .center, spacing: 4) {
            if model.screens.boardLabelAtTop && model.screens.boardShowLabels && hasSymbol {
                boardCellLabel(cell, size: labelSize, hasSymbol: true, rowSpan: rowSpan, columnSpan: columnSpan)
            }

            if hasSymbol, let imageUrl = trimmed(cell?.imageUrl), let url = URL(string: imageUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    case .failure(_):
                        EmptyView()
                    case .empty:
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 24)
                    @unknown default:
                        EmptyView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if (!model.screens.boardLabelAtTop || !hasSymbol) && model.screens.boardShowLabels {
                boardCellLabel(cell, size: labelSize, hasSymbol: hasSymbol, rowSpan: rowSpan, columnSpan: columnSpan)
            }

            if let linkedBoardId = trimmed(cell?.linkedBoardId) {
                Label(model.screens.boardDisplayName(id: linkedBoardId), systemImage: "arrowshape.turn.up.right")
                    .font(.caption2)
                    .foregroundStyle(contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.secondary))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(8)
        .frame(width: width, height: height)
        .background(
            RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                .fill(model.settings.highContrastMode ? Color(.systemBackground) : colorFromHex(cell?.resolvedBackgroundColor, fallback: Color(.tertiarySystemBackground)))
        )
        .overlay(
            RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                .stroke(model.settings.highContrastMode ? Color.primary : colorFromHex(cell?.borderColor, fallback: isLinked ? .accentColor : Color(.separator)),
                    lineWidth: model.settings.highContrastMode ? 2 : (isLinked ? 1.5 : 1))
        )
        .overlay {
            if isGuided {
                let shape = RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                let ringColor: Color = model.settings.highContrastMode ? .primary : .accentColor
                ZStack(alignment: .topLeading) {
                    shape
                        .stroke(ringColor, lineWidth: 5)
                        .overlay(shape.inset(by: 3).stroke(Color.white, lineWidth: 2))
                    Image(systemName: "safari.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(model.settings.highContrastMode ? Color(.systemBackground) : Color.white)
                        .padding(5)
                        .background(ringColor, in: Circle())
                        .padding(6)
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            if isHighlighted {
                SelectionHighlightOutline(shape: RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height)))
            }
            if let buttonId = cell?.buttonId, touchExitHighlightedButtonId == buttonId {
                let shape = RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                shape
                    .fill((model.settings.highContrastMode ? Color.primary : Color.accentColor).opacity(0.16))
                    .overlay(shape.stroke(model.settings.highContrastMode ? Color.primary : Color.accentColor, lineWidth: 4))
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        )
    }

    func boardCellCornerRadius(_ cell: BoardCellInfo?, width: CGFloat, height: CGFloat) -> CGFloat {
        switch cell?.shape {
        // Rounded is the default shape (Android 945427e9), at 16pt like Typing tiles.
        case "rounded", "speech", "thought": return 16
        case "pill": return min(width, height) / 2
        default: return 0
        }
    }

    @ViewBuilder
    func boardCellLabel(_ cell: BoardCellInfo?, size: CGFloat, hasSymbol: Bool, rowSpan: Int = 1, columnSpan: Int = 1) -> some View {
        let expanded = !hasSymbol ? ScreenButtonText.expandedText(label: cell?.label, vocalization: cell?.vocalization) : nil
        let text = expanded ?? boardCellDisplayLabel(cell)
        let foreground = model.settings.highContrastMode
            ? Color.primary
            : contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.primary)
        if !text.isEmpty {
            if !hasSymbol && (expanded != nil || !ScreenButtonText.isShortLabel(text)) {
                // Speech paragraphs and longer sentences read cleanly from top-leading without
                // duplicate vocalization lines or two-line truncation.
                let readingBase: CGFloat = horizontalSizeClass == .regular ? 24 : 17
                let readingSize = readingBase * min(model.screens.boardFieldFontScale(rowSpan: rowSpan, columnSpan: columnSpan), 1.35)
                Text(text)
                    .font(.system(size: readingSize, weight: .medium))
                    .multilineTextAlignment(.leading)
                    .lineLimit(expanded != nil ? nil : 4)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(foreground)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else {
                Text(text)
                    .font(.system(size: size, weight: .semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.5)
                    .foregroundStyle(foreground)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: - Canvas Field Drag & Resize

    func fieldNudgeButton(_ key: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(minWidth: 56, minHeight: 56)
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(Text(key))
    }

    func spanAdjustRow(
        title: LocalizedStringKey,
        value: Int,
        decreaseKey: LocalizedStringKey,
        increaseKey: LocalizedStringKey,
        canDecrease: Bool,
        canIncrease: Bool,
        decrease: @escaping () -> Void,
        increase: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: decrease) {
                Image(systemName: "minus")
                    .frame(minWidth: 56, minHeight: 56)
            }
            .buttonStyle(.bordered)
            .disabled(!canDecrease)
            .accessibilityLabel(Text(decreaseKey))
            Text("\(value)")
                .font(.title3)
                .frame(minWidth: 32)
                .accessibilityHidden(true)
            Button(action: increase) {
                Image(systemName: "plus")
                    .frame(minWidth: 56, minHeight: 56)
            }
            .buttonStyle(.bordered)
            .disabled(!canIncrease)
            .accessibilityLabel(Text(increaseKey))
        }
    }

    func selectField(_ anchor: BoardCellAnchor) {
        selectedFieldAnchor = anchor
        Task {
            let options = await model.screens.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
            await MainActor.run {
                guard selectedFieldAnchor == anchor else { return }
                selectedFieldSpans = options
            }
        }
    }

    func fieldDragOffset(for buttonId: String?, fallback: CGSize) -> CGSize {
        guard let buttonId, let drag = fieldDrag, drag.buttonId == buttonId else { return fallback }
        return CGSize(width: fallback.width + drag.translation.width, height: fallback.height + drag.translation.height)
    }

    func cellAddress(at location: CGPoint, metrics: BoardGridMetrics) -> (row: Int, column: Int)? {
        guard let resolved = metrics.layout.cellAddress(at: location),
              resolved.row < metrics.rows,
              resolved.column < metrics.columns else { return nil }
        return resolved
    }

    func touchExitTarget(at location: CGPoint, layout: BoardFoldLayout, isEditMode: Bool) -> (field: BoardFieldItem, cell: BoardCellInfo)? {
        for field in model.screens.boardFieldItems {
            guard let cell = model.screens.cellAt(row: field.row, col: field.column) else { continue }
            guard model.screens.boardButtonIsVisible(hidden: cell.hidden, isEditMode: isEditMode, showHiddenButtons: showHiddenButtons) else { continue }
            let rect = layout.frame(row: field.row, column: field.column, rowSpan: field.rowSpan, columnSpan: field.columnSpan)
            if rect.contains(location) {
                return (field, cell)
            }
        }
        return nil
    }

    func touchExitGesture(pageId: String, layout: BoardFoldLayout, layoutRevision: Int, isEditMode: Bool) -> some Gesture {
        // Use .local so coordinates share the exact origin of the framed ZStack / foldLayout.
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .updating($touchExitGestureActive) { _, state, _ in
                state = true
            }
            .onChanged { value in
                // A finger held across a Page or layout change must lift before it can select again.
                guard !touchExitLatch.ignoresFinger else { return }
                // The model published a new layout this gesture has not rendered yet: hit-testing
                // would mix the captured geometry with new Buttons.
                guard model.screens.boardCellsReady(for: pageId),
                      model.screens.boardLayoutRevision == layoutRevision else {
                    cancelTouchExit()
                    return
                }
                let previousTargetId = touchExitController.state.activeTargetId
                let hit = touchExitTarget(at: value.location, layout: layout, isEditMode: isEditMode)
                let effect: (any TouchExitEffect)?
                if !touchExitController.state.isTracking {
                    effect = touchExitController.begin(targetId: hit?.cell.buttonId)
                } else {
                    effect = touchExitController.move(targetId: hit?.cell.buttonId)
                }
                touchExitHighlightedButtonId = touchExitController.state.activeTargetId
                if effect is TouchExitEffectPreview,
                   let cell = hit?.cell,
                   model.settings.auditoryFishingEnabled,
                   !model.settings.inputIsPaused {
                    if let preview = boardCellPreviewText(cell) {
                        model.communication.previewSpeech(preview, languageTag: cell.language)
                    } else {
                        model.communication.cancelPreviewSpeech()
                    }
                } else if previousTargetId != nil && hit == nil {
                    model.communication.cancelPreviewSpeech()
                }
            }
            .onEnded { value in
                model.communication.cancelPreviewSpeech()
                let latched = touchExitLatch.lift()
                guard !latched,
                      model.screens.boardCellsReady(for: pageId),
                      model.screens.boardLayoutRevision == layoutRevision else {
                    cancelTouchExit(latchUntilLift: false)
                    return
                }
                let hit = touchExitTarget(at: value.location, layout: layout, isEditMode: isEditMode)
                let effect = touchExitController.release(targetId: hit?.cell.buttonId)
                touchExitHighlightedButtonId = touchExitController.state.activeTargetId
                if effect is TouchExitEffectActivate, let hit {
                    activateBoardCell(
                        hit.cell,
                        pageId: pageId,
                        row: hit.field.row,
                        col: hit.field.column,
                        sourceCellId: "\(hit.field.row):\(hit.field.column)"
                    )
                }
            }
    }

    func cancelTouchExit(latchUntilLift: Bool = true) {
        if latchUntilLift {
            touchExitLatch.cancel(fingerDown: touchExitGestureActive)
        }
        _ = touchExitController.cancel()
        model.communication.cancelPreviewSpeech()
        if touchExitHighlightedButtonId != nil {
            touchExitHighlightedButtonId = nil
        }
    }

    func editMoveGesture(anchor: BoardCellAnchor, cell: BoardCellInfo?, isEditMode: Bool, metrics: BoardGridMetrics) -> some Gesture {
        let enabled = isEditMode && cell != nil && model.screens.canEditSelectedBoardSet
        return LongPressGesture(minimumDuration: enabled ? 0.35 : 10_000)
            .sequenced(before: DragGesture(minimumDistance: 8, coordinateSpace: .named("boardGrid")))
            .onChanged { value in
                guard enabled, let cell else { return }
                if case .second(true, let drag?) = value {
                    suppressNextCellTap = true
                    let target = cellAddress(at: drag.location, metrics: metrics)
                    fieldDrag = FieldDragState(
                        buttonId: cell.buttonId,
                        fromRow: anchor.row,
                        fromColumn: anchor.column,
                        translation: drag.translation,
                        targetRow: target?.row ?? anchor.row,
                        targetColumn: target?.column ?? anchor.column
                    )
                }
            }
            .onEnded { value in
                guard enabled, let cell else { return }
                guard case .second(true, let drag?) = value else { return }
                let target = cellAddress(at: drag.location, metrics: metrics) ?? (anchor.row, anchor.column)
                let buttonId = cell.buttonId
                fieldDrag = nil
                suppressNextCellTap = true
                guard target.0 != anchor.row || target.1 != anchor.column else { return }
                Task {
                    let moved = await model.screens.moveBoardField(
                        fromRow: anchor.row,
                        fromCol: anchor.column,
                        toRow: target.0,
                        toCol: target.1
                    )
                    if moved, let item = model.screens.boardFieldItems.first(where: { $0.buttonId == buttonId }) {
                        selectField(BoardCellAnchor(row: item.row, column: item.column))
                    } else if !moved {
                        model.screens.boardStatusMessage = NSLocalizedString("boardset.cell.move.blocked", comment: "")
                    }
                }
            }
    }

    func fieldEditChrome(layout: BoardFoldLayout, contentWidth: CGFloat, contentHeight: CGFloat, rows: Int, columns: Int) -> some View {
        return ZStack(alignment: .topLeading) {
        if let drag = fieldDrag {
            let target = model.screens.boardFieldItems.first { item in
                drag.targetRow >= item.row && drag.targetRow < item.row + item.rowSpan &&
                    drag.targetColumn >= item.column && drag.targetColumn < item.column + item.columnSpan
            }
            if let target, target.buttonId != drag.buttonId {
                let rect = layout.frame(
                    row: target.row,
                    column: target.column,
                    rowSpan: target.rowSpan,
                    columnSpan: target.columnSpan
                )
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.accentColor, lineWidth: 3)
                    .frame(width: rect.width, height: rect.height)
                    .offset(x: rect.minX, y: rect.minY)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        if let anchor = selectedFieldAnchor,
           let field = model.screens.boardFieldItems.first(where: { $0.row == anchor.row && $0.column == anchor.column && $0.buttonId != nil }) {
            let span = resizePreview ?? GridFieldSpanInfo(rows: field.rowSpan, columns: field.columnSpan)
            let rect = layout.frame(
                row: field.row,
                column: field.column,
                rowSpan: span.rows,
                columnSpan: span.columns
            )
            RoundedRectangle(cornerRadius: 12)
                .stroke(resizePreview == nil || resizePreviewValid ? Color.accentColor : Color.red, lineWidth: 3)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill((resizePreviewValid ? Color.accentColor : Color.red).opacity(resizePreview == nil ? 0 : 0.18))
                )
                .frame(width: rect.width, height: rect.height)
                .offset(x: rect.minX, y: rect.minY)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            resizeHandle(.end, field: field, layout: layout, rows: rows, columns: columns, span: span)
            resizeHandle(.bottom, field: field, layout: layout, rows: rows, columns: columns, span: span)
            resizeHandle(.corner, field: field, layout: layout, rows: rows, columns: columns, span: span)
        }
        }
        .frame(width: max(0, contentWidth), height: max(0, contentHeight), alignment: .topLeading)
    }

    func resizeHandle(
        _ edge: FieldResizeEdge,
        field: BoardFieldItem,
        layout: BoardFoldLayout,
        rows: Int,
        columns: Int,
        span: GridFieldSpanInfo
    ) -> some View {
        let rect = layout.frame(
            row: field.row,
            column: field.column,
            rowSpan: span.rows,
            columnSpan: span.columns
        )
        let centerX: CGFloat = switch edge {
        case .bottom: rect.midX
        case .end, .corner: rect.maxX
        }
        let centerY: CGFloat = switch edge {
        case .end: rect.midY
        case .bottom, .corner: rect.maxY
        }
        let metrics = BoardGridMetrics(
            layout: layout,
            rows: rows,
            columns: columns
        )
        return Circle()
            .fill(Color.accentColor)
            .frame(width: 44, height: 44)
            .overlay {
                Image(systemName: edge == .corner ? "arrow.up.left.and.arrow.down.right" : "circle.fill")
                    .font(edge == .corner ? .body : .caption2)
                    .foregroundStyle(Color(.systemBackground))
                    .accessibilityHidden(true)
            }
            .offset(x: centerX - 22, y: centerY - 22)
            .accessibilityLabel(Text("boardset.cell.merge"))
            .accessibilityAddTraits(.isButton)
            .gesture(
                DragGesture(minimumDistance: 4, coordinateSpace: .named("boardGrid"))
                    .onChanged { value in
                        guard let address = cellAddress(at: value.location, metrics: metrics) else {
                            resizePreviewValid = false
                            return
                        }
                        let next = snappedSpan(edge: edge, field: field, row: address.row, column: address.column)
                        resizePreview = next
                        resizePreviewValid = selectedFieldSpans.contains(next) || next == GridFieldSpanInfo(rows: field.rowSpan, columns: field.columnSpan)
                    }
                    .onEnded { value in
                        let address = cellAddress(at: value.location, metrics: metrics)
                        let next = address.map { snappedSpan(edge: edge, field: field, row: $0.row, column: $0.column) }
                        resizePreview = nil
                        guard let next, selectedFieldSpans.contains(next),
                              next.rows != field.rowSpan || next.columns != field.columnSpan else {
                            if next != nil && (next?.rows != field.rowSpan || next?.columns != field.columnSpan) {
                                model.screens.boardStatusMessage = NSLocalizedString("boardset.cell.size.blocked", comment: "")
                            }
                            return
                        }
                        Task {
                            await model.screens.resizeSelectedBoardField(
                                row: field.row,
                                col: field.column,
                                rows: next.rows,
                                columns: next.columns
                            )
                            selectField(BoardCellAnchor(row: field.row, column: field.column))
                        }
                    }
            )
    }

    func snappedSpan(edge: FieldResizeEdge, field: BoardFieldItem, row: Int, column: Int) -> GridFieldSpanInfo {
        GridFieldSpanInfo(
            rows: edge == .end ? field.rowSpan : max(1, row - field.row + 1),
            columns: edge == .bottom ? field.columnSpan : max(1, column - field.column + 1)
        )
    }

    func moveField(from anchor: BoardCellAnchor, directionRow: Int, directionColumn: Int, fromEditor: Bool = false) async {
        guard let field = model.screens.boardFieldItems.first(where: { $0.row == anchor.row && $0.column == anchor.column && $0.buttonId != nil }) else { return }
        let targetRow = anchor.row + (directionRow > 0 ? field.rowSpan : directionRow)
        let targetColumn = anchor.column + (directionColumn > 0 ? field.columnSpan : directionColumn)
        guard targetRow >= 0, targetColumn >= 0 else {
            model.screens.boardStatusMessage = NSLocalizedString("boardset.cell.move.blocked", comment: "")
            return
        }
        let buttonId = field.buttonId
        let moved = await model.screens.moveBoardField(fromRow: anchor.row, fromCol: anchor.column, toRow: targetRow, toCol: targetColumn)
        guard moved, let buttonId, let item = model.screens.boardFieldItems.first(where: { $0.buttonId == buttonId }) else {
            model.screens.boardStatusMessage = NSLocalizedString("boardset.cell.move.blocked", comment: "")
            return
        }
        let next = BoardCellAnchor(row: item.row, column: item.column)
        selectField(next)
        if fromEditor {
            editingRow = next.row
            editingCol = next.column
            await reloadEditingSpans(anchor: next)
        }
    }

    func resizeField(_ anchor: BoardCellAnchor, deltaRows: Int, deltaColumns: Int, fromEditor: Bool = false) async {
        guard let field = model.screens.boardFieldItems.first(where: { $0.row == anchor.row && $0.column == anchor.column && $0.buttonId != nil }) else { return }
        let options = await model.screens.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
        let next = GridFieldSpanInfo(rows: field.rowSpan + deltaRows, columns: field.columnSpan + deltaColumns)
        guard options.contains(next) else {
            model.screens.boardStatusMessage = NSLocalizedString("boardset.cell.size.blocked", comment: "")
            return
        }
        await model.screens.resizeSelectedBoardField(row: anchor.row, col: anchor.column, rows: next.rows, columns: next.columns)
        selectedFieldSpans = options
        if fromEditor {
            editingSpan = next.rows == 1 && next.columns == 1 ? .single : .custom(next)
            editingSpanOptions = await model.screens.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
        } else {
            selectField(anchor)
        }
    }

    func reloadEditingSpans(anchor: BoardCellAnchor) async {
        let options = await model.screens.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
        let field = model.screens.boardFieldItems.first { $0.row == anchor.row && $0.column == anchor.column }
        editingSpanOptions = options
        if let field, let match = options.first(where: { $0.rows == field.rowSpan && $0.columns == field.columnSpan }) {
            editingSpan = match.rows == 1 && match.columns == 1 ? .single : .custom(match)
        }
    }
}

/// Keeps a finger that was down when Touch Exit was cancelled (Page, Button, or layout change)
/// from highlighting or activating anything until it lifts. A fresh touch then works normally.
struct TouchExitLiftLatch: Equatable {
    private(set) var ignoresFinger = false

    /// Records a cancel; latches only when a finger is currently down.
    mutating func cancel(fingerDown: Bool) {
        if fingerDown { ignoresFinger = true }
    }

    /// Clears the latch when the grid that owned the held finger mounts or unmounts.
    mutating func reset() {
        ignoresFinger = false
    }

    /// Call on lift (or gesture end). Returns true when the lifted finger was latched and
    /// must not activate anything, and clears the latch for the next touch.
    mutating func lift() -> Bool {
        defer { ignoresFinger = false }
        return ignoresFinger
    }
}
