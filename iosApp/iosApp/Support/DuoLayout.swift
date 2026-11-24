import SwiftUI

/// Window-width classes driven by the active scene's dimensions, never by `UIDevice.userInterfaceIdiom`.
/// On iPhone Duo, the outer cover display (466×678 pt) is `.compact`, the unfolded inner display
/// in portrait (669×951 pt) is `.medium`, and unfolded landscape (951×669 pt) is `.expanded`.
enum WindowLayoutClass: Equatable {
    /// Under 600 pt: standard iPhones, iPhone Duo outer display, narrow split windows.
    case compact
    /// 600..<840 pt: iPhone Duo unfolded portrait, portrait iPads, half-screen multitasking.
    case medium
    /// 840 pt and wider: iPhone Duo unfolded landscape, landscape iPads.
    case expanded

    var isLarge: Bool { self != .compact }

    static func of(width: CGFloat) -> WindowLayoutClass {
        if width >= 840 { return .expanded }
        if width >= 600 { return .medium }
        return .compact
    }

    /// Wide two-pane Typing needs at least `.medium` width and enough resting height for the Message,
    /// Phrase rows, and speech controls together. Phone landscape (including Duo outer landscape
    /// at 678×466 pt) stays on the compact phone composer; raising the keyboard never changes it.
    static func usesWideTyping(size: CGSize, restingHeight: CGFloat? = nil, keyboardVisible: Bool = false) -> Bool {
        let effectiveHeight = keyboardVisible ? max(size.height, restingHeight ?? size.height) : size.height
        return of(width: size.width).isLarge && effectiveHeight >= 480
    }

    /// Side control panel width in the wide Typing layout.
    static func typingSidePanelWidth(for containerWidth: CGFloat) -> CGFloat {
        of(width: containerWidth) == .expanded ? 288 : 232
    }

    /// Comfortable touch-target minimum height for Phrase tiles by size class.
    static func phraseTileMinHeight(isLarge: Bool) -> CGFloat {
        isLarge ? 112 : 88
    }
}

/// An active hardware fold division (crease) within a view's coordinate space.
struct FoldDivision: Equatable {
    enum Axis: Equatable {
        /// Horizontal crease splitting top and bottom halves (tabletop / laptop posture).
        case horizontal
        /// Vertical crease splitting leading and trailing halves (book posture).
        case vertical
    }

    let axis: Axis
    /// Frame of the reserved division region in the querying view's coordinate space.
    let rect: CGRect

    /// Resolves the primary active fold division intersecting `containerSize`, if any.
    static func from(rects: [CGRect], in containerSize: CGSize) -> FoldDivision? {
        guard containerSize.width > 0, containerSize.height > 0 else { return nil }
        let bounds = CGRect(origin: .zero, size: containerSize)
        guard let candidate = rects
            .map({ $0.intersection(bounds) })
            .first(where: { !$0.isNull && ($0.width > 0.5 || $0.height > 0.5) })
        else { return nil }

        let axis: Axis = candidate.width > candidate.height ? .horizontal : .vertical
        return FoldDivision(axis: axis, rect: candidate)
    }
}

/// Computes Screen grid row/column coordinates while avoiding an active hardware fold crease
/// without changing the grid's row×column motor-plan topology.
struct BoardFoldLayout: Equatable {
    let columnOrigins: [CGFloat]
    let columnWidths: [CGFloat]
    let rowOrigins: [CGFloat]
    let rowHeights: [CGFloat]
    let gap: CGFloat

    /// Uniform cell size fallback for single-cell metrics (e.g. drag quantization).
    var nominalCellWidth: CGFloat { columnWidths.first ?? 1 }
    var nominalCellHeight: CGFloat { rowHeights.first ?? 1 }

    static func compute(
        rows: Int,
        columns: Int,
        contentWidth: CGFloat,
        contentHeight: CGFloat,
        gap: CGFloat,
        gridInset: CGFloat,
        division: FoldDivision?
    ) -> BoardFoldLayout {
        let safeRows = max(1, rows)
        let safeCols = max(1, columns)
        let safeWidth = max(0, contentWidth)
        let safeHeight = max(0, contentHeight)

        let (colOrigins, colWidths) = axisTracks(
            count: safeCols,
            totalLength: safeWidth,
            gap: gap,
            inset: gridInset,
            divisionRange: division?.axis == .vertical
                ? (division!.rect.minX - gridInset)...(division!.rect.maxX - gridInset)
                : nil
        )
        let (rowOrigins, rowHeights) = axisTracks(
            count: safeRows,
            totalLength: safeHeight,
            gap: gap,
            inset: gridInset,
            divisionRange: division?.axis == .horizontal
                ? (division!.rect.minY - gridInset)...(division!.rect.maxY - gridInset)
                : nil
        )
        return BoardFoldLayout(
            columnOrigins: colOrigins,
            columnWidths: colWidths,
            rowOrigins: rowOrigins,
            rowHeights: rowHeights,
            gap: gap
        )
    }

    /// Frame for a cell spanning `rowSpan` rows and `columnSpan` columns inside the grid canvas.
    func frame(row: Int, column: Int, rowSpan: Int, columnSpan: Int) -> CGRect {
        let r0 = min(max(row, 0), rowOrigins.count - 1)
        let r1 = min(max(r0 + max(1, rowSpan) - 1, r0), rowOrigins.count - 1)
        let c0 = min(max(column, 0), columnOrigins.count - 1)
        let c1 = min(max(c0 + max(1, columnSpan) - 1, c0), columnOrigins.count - 1)
        let x = columnOrigins[c0]
        let maxX = columnOrigins[c1] + columnWidths[c1]
        let y = rowOrigins[r0]
        let maxY = rowOrigins[r1] + rowHeights[r1]
        return CGRect(x: x, y: y, width: max(0, maxX - x), height: max(0, maxY - y))
    }

    /// Resolves the `(row, column)` under `point` in the grid's coordinate space, or `nil`
    /// if `point` falls inside a gutter or an active fold crease.
    func cellAddress(at point: CGPoint) -> (row: Int, column: Int)? {
        guard let column = columnOrigins.indices.first(where: { col in
            point.x >= columnOrigins[col] - 0.5 && point.x <= columnOrigins[col] + columnWidths[col] + 0.5
        }), let row = rowOrigins.indices.first(where: { r in
            point.y >= rowOrigins[r] - 0.5 && point.y <= rowOrigins[r] + rowHeights[r] + 0.5
        }) else {
            return nil
        }
        return (row, column)
    }

    /// Computes track origins and lengths along one axis. When an active fold division crosses
    /// inside a multi-track grid, splits tracks cleanly across the two sides of the crease.
    private static func axisTracks(
        count: Int,
        totalLength: CGFloat,
        gap: CGFloat,
        inset: CGFloat,
        divisionRange: ClosedRange<CGFloat>?
    ) -> ([CGFloat], [CGFloat]) {
        guard count > 0 else { return ([], []) }

        if let range = divisionRange, count >= 2, totalLength > 0 {
            let clampedStart = min(max(range.lowerBound, 0), totalLength)
            let clampedEnd = min(max(range.upperBound, clampedStart), totalLength)
            let divisionCenter = (clampedStart + clampedEnd) / 2
            let leadingSpace = clampedStart
            let trailingSpace = max(0, totalLength - clampedEnd)

            // Only split if there is meaningful space on both sides of the crease.
            if leadingSpace > gap * 2, trailingSpace > gap * 2 {
                let proportionalSplit = Int((divisionCenter / totalLength * CGFloat(count)).rounded())
                let leadingCount = min(max(proportionalSplit, 1), count - 1)
                let trailingCount = count - leadingCount

                let leadingGapTotal = gap * CGFloat(max(0, leadingCount - 1))
                let trailingGapTotal = gap * CGFloat(max(0, trailingCount - 1))
                let leadingCell = max(0, (leadingSpace - gap - leadingGapTotal) / CGFloat(leadingCount))
                let trailingCell = max(0, (trailingSpace - gap - trailingGapTotal) / CGFloat(trailingCount))

                if leadingCell >= 24, trailingCell >= 24 {
                    var origins: [CGFloat] = []
                    var lengths: [CGFloat] = []
                    origins.reserveCapacity(count)
                    lengths.reserveCapacity(count)

                    for i in 0..<leadingCount {
                        origins.append(CGFloat(i) * (leadingCell + gap))
                        lengths.append(leadingCell)
                    }
                    let trailingStart = clampedEnd + gap
                    for i in 0..<trailingCount {
                        origins.append(trailingStart + CGFloat(i) * (trailingCell + gap))
                        lengths.append(trailingCell)
                    }
                    return (origins, lengths)
                }
            }
        }

        let cellLength = max(0, (totalLength - gap * CGFloat(max(0, count - 1))) / CGFloat(count))
        let origins = (0..<count).map { CGFloat($0) * (cellLength + gap) }
        let lengths = Array(repeating: cellLength, count: count)
        return (origins, lengths)
    }
}

extension GeometryProxy {
    /// Active hardware fold division in this geometry proxy's coordinate space (iOS 27.1+ on iPhone Duo).
    var activeFoldDivision: FoldDivision? {
        #if compiler(>=6.4.1)
        if #available(iOS 27.1, *) {
            let activeRects = reservedRegions(kind: .division)
                .filter(\.isActive)
                .map(\.frame)
            return FoldDivision.from(rects: activeRects, in: size)
        }
        #endif
        return nil
    }

    /// Extra top inset needed to stay clear of any active hardware occlusion (such as an inner
    /// display camera cutout) when the system status bar is hidden.
    var topOcclusionClearance: CGFloat {
        #if compiler(>=6.4.1)
        if #available(iOS 27.1, *) {
            let topOcclusions = reservedRegions(kind: .occlusion)
                .filter { $0.isActive && $0.frame.minY < size.height * 0.25 }
            return topOcclusions.map(\.frame.maxY).max() ?? 0
        }
        #endif
        return 0
    }
}

/// Places a primary and secondary pane using iOS 27.1's fold-aware `ArrangementView` when available
/// and a fold division is active, falling back to a responsive split layout otherwise.
struct AdaptiveFoldSplitView<Primary: View, Secondary: View>: View {
    let splitAxis: Axis
    let primaryRatio: CGFloat?
    let spacing: CGFloat
    @ViewBuilder let primary: () -> Primary
    @ViewBuilder let secondary: () -> Secondary

    init(
        splitAxis: Axis = .horizontal,
        primaryRatio: CGFloat? = nil,
        spacing: CGFloat = 12,
        @ViewBuilder primary: @escaping () -> Primary,
        @ViewBuilder secondary: @escaping () -> Secondary
    ) {
        self.splitAxis = splitAxis
        self.primaryRatio = primaryRatio
        self.spacing = spacing
        self.primary = primary
        self.secondary = secondary
    }

    var body: some View {
        #if compiler(>=6.4.1)
        if #available(iOS 27.1, *) {
            ArrangementView {
                primary()
                    .splitArrangementLayoutRatio(primaryRatio)
            } secondary: {
                secondary()
            }
            .arrangementViewStyle(.split)
        } else {
            fallbackLayout
        }
        #else
        fallbackLayout
        #endif
    }

    @ViewBuilder
    private var fallbackLayout: some View {
        if splitAxis == .horizontal {
            HStack(spacing: spacing) {
                primary()
                secondary()
            }
        } else {
            VStack(spacing: spacing) {
                primary()
                secondary()
            }
        }
    }
}
