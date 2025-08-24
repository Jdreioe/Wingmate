import SwiftUI
import UIKit
import Shared

struct PhrasesGridView<Cell: View>: View {
    let columns: [GridItem]
    let phrases: [Shared.Phrase]
    let onAdd: () -> Void
    let onItemFramesChange: ([String: CGRect]) -> Void
    @ViewBuilder let cell: (Shared.Phrase) -> Cell

    var body: some View {
        let rowSpacing: CGFloat = UIDevice.current.userInterfaceIdiom == .pad ? 12 : 8
        ScrollView {
            LazyVGrid(columns: columns, spacing: rowSpacing) {
                Button(action: onAdd) {
                    VStack {
                        Image(systemName: "plus.circle.fill").font(.system(size: 28))
                        Text("phrase.add.tile")
                    }
            .frame(maxWidth: .infinity, minHeight: UIDevice.current.userInterfaceIdiom == .pad ? 140 : 100)
                    .background(Color.secondary.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                ForEach(phrases, id: \.id) { p in
                    cell(p)
                }
            }
            .padding(.top, 4)
        }
        .coordinateSpace(name: "gridSpace")
        .onPreferenceChange(ItemFramePref.self, perform: onItemFramesChange)
    }
}
