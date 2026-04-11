import SwiftUI
import UIKit
import Shared

struct PhrasesGridView<Cell: View>: View {
    let columns: [GridItem]
    let phrases: [Shared.Phrase]
    let onAdd: () -> Void
    let onItemFramesChange: ([String: CGRect]) -> Void
    @ViewBuilder let cell: (Shared.Phrase) -> Cell
    
    // Add wiggle mode state to disable add button during reordering
    var isWiggleMode: Bool = false
    // Explicit flag to hide the Add button (e.g., in History mode)
    var hideAddButton: Bool = false
    var scanEnabled: Bool = false
    var includeInScanArea: Bool = true
    var scanOrder: String = "row-major"

    private var columnCount: Int {
        max(1, columns.count)
    }

    private func normalizedScanOrder(_ value: String) -> String {
        switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "column-major":
            return "column-major"
        case "linear":
            return "linear"
        default:
            return "row-major"
        }
    }

    private func accessibilityScanIndex(for visualIndex: Int) -> Int {
        let order = normalizedScanOrder(scanOrder)
        if order == "linear" || order == "row-major" {
            return visualIndex
        }

        let rows = Int(ceil(Double(phrases.count) / Double(columnCount)))
        let row = visualIndex / columnCount
        let col = visualIndex % columnCount
        return (col * rows) + row
    }

    private func accessibilityPriority(for visualIndex: Int) -> Double {
        Double(100_000 - accessibilityScanIndex(for: visualIndex))
    }

    var body: some View {
        let rowSpacing: CGFloat = UIDevice.current.userInterfaceIdiom == .pad ? 12 : 8
        ScrollView {
            LazyVGrid(columns: columns, spacing: rowSpacing) {
                // Hide add button during wiggle mode to avoid confusion
                if !isWiggleMode && !hideAddButton {
                    Button(action: onAdd) {
                        VStack {
                            Image(systemName: "plus.circle.fill").font(.system(size: 28))
                            Text("phrase.add.tile")
                        }
                        .frame(maxWidth: .infinity, minHeight: UIDevice.current.userInterfaceIdiom == .pad ? 140 : 100)
                        .background(Color.secondary.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .transition(.scale.combined(with: .opacity))
                    .accessibilitySortPriority(Double(-1))
                }
                ForEach(Array(phrases.enumerated()), id: \.element.id) { index, p in
                    cell(p)
                        .accessibilitySortPriority(accessibilityPriority(for: index))
                }
            }
            .padding(.top, 4)
            .animation(.easeInOut(duration: 0.2), value: isWiggleMode)
            .accessibilityElement(children: .contain)
        }
        .coordinateSpace(name: "gridSpace")
        .onPreferenceChange(ItemFramePref.self, perform: onItemFramesChange)
        .accessibilityHidden(scanEnabled && !includeInScanArea)
    }
}
