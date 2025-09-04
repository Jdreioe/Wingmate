import SwiftUI
import Shared

struct ReorderablePhraseCell: View {
    @ObservedObject var model: IosViewModel
    let phrase: Shared.Phrase
    let recorder: AudioRecorder
    @Binding var recordingForPhraseId: String?

    // Reorder state bindings
    @Binding var wiggleMode: Bool
    @Binding var gridLocal: [Shared.Phrase]
    @Binding var draggingId: String?
    @Binding var draggingOffset: CGSize
    @Binding var dragStartFrame: CGRect?
    let itemFrames: [String: CGRect]

    // Callbacks
    let onEdit: () -> Void
    let requestMic: (String) -> Void
    let onDelete: (String) -> Void
    let commitMove: (String, Int) -> Void

    @State private var lastToIndex: Int? = nil

    var body: some View {
        let isDragging = draggingId == phrase.id
        let baseView = PhraseItemView(
            model: model,
            phrase: phrase,
            recorder: recorder,
            recordingForPhraseId: $recordingForPhraseId,
            onEdit: onEdit,
            requestMic: requestMic,
            onDelete: onDelete,
            wiggle: wiggleMode && !isDragging // Disable wiggle when dragging
        )
        .opacity(isDragging ? 0.9 : 1.0)
        .scaleEffect(isDragging ? 1.02 : 1.0)
        .shadow(color: .black.opacity(isDragging ? 0.2 : 0), radius: isDragging ? 6 : 0, x: 0, y: isDragging ? 3 : 0)
        .allowsHitTesting(!wiggleMode) // Disable hit testing during wiggle mode
        .background(
            GeometryReader { proxy in
                Color.clear.preference(key: ItemFramePref.self, value: [phrase.id: proxy.frame(in: .named("gridSpace"))])
            }
        )
        .offset(isDragging ? draggingOffset : .zero)
        .zIndex(isDragging ? 999 : 0) // Higher z-index for dragging item
        .contentShape(Rectangle())

        if wiggleMode {
            baseView
                .gesture(
                    DragGesture(coordinateSpace: .named("gridSpace"))
                        .onChanged { value in
                            handleDragChanged(value)
                        }
                        .onEnded { _ in
                            handleDragEnded()
                        }
                )
                // Remove the animation that was causing conflicts
        } else {
            baseView
        }
    }
    
    private func handleDragChanged(_ value: DragGesture.Value) {
        // Only allow one item to be dragged at a time
        guard draggingId == nil || draggingId == phrase.id else { return }
        if draggingId == nil {
            draggingId = phrase.id
            dragStartFrame = itemFrames[phrase.id]
            lastToIndex = gridLocal.firstIndex(where: { $0.id == phrase.id })
        }
        draggingOffset = value.translation

        // Calculate the current center of the dragged item
        guard let startFrame = dragStartFrame else { return }
        let currentCenter = CGPoint(
            x: startFrame.midX + value.translation.width,
            y: startFrame.midY + value.translation.height
        )

        // Find the nearest item (excluding self)
        if let targetId = nearestId(to: currentCenter, excluding: phrase.id),
           let fromIndex = gridLocal.firstIndex(where: { $0.id == phrase.id }),
           let toIndex = gridLocal.firstIndex(where: { $0.id == targetId }),
           fromIndex != toIndex,
           lastToIndex != toIndex {
            withAnimation(.easeInOut(duration: 0.15)) {
                let item = gridLocal.remove(at: fromIndex)
                gridLocal.insert(item, at: toIndex)
                lastToIndex = toIndex
            }
        }
    }
    
    private func handleDragEnded() {
        guard let movingId = draggingId else { return }
        // Calculate where to drop based on final position
        guard let startFrame = dragStartFrame else {
            // Reset drag state
            draggingId = nil
            draggingOffset = .zero
            dragStartFrame = nil
            lastToIndex = nil
            return
        }
        let finalCenter = CGPoint(
            x: startFrame.midX + draggingOffset.width,
            y: startFrame.midY + draggingOffset.height
        )
        if let targetId = nearestId(to: finalCenter, excluding: phrase.id),
           let fromIndex = gridLocal.firstIndex(where: { $0.id == movingId }),
           let toIndex = gridLocal.firstIndex(where: { $0.id == targetId }),
           fromIndex != toIndex {
            let item = gridLocal.remove(at: fromIndex)
            gridLocal.insert(item, at: toIndex)
            commitMove(movingId, toIndex)
        }
        // Reset drag state with animation
        withAnimation(.spring(response: 0.4, dampingFraction: 0.8, blendDuration: 0.1)) {
            draggingId = nil
            draggingOffset = .zero
            dragStartFrame = nil
            lastToIndex = nil
        }
    }

    // Local helper for nearest target by center distance
    private func nearestId(to point: CGPoint, excluding: String) -> String? {
        var best: (String, CGFloat)? = nil
        for (id, frame) in itemFrames where id != excluding {
            let center = CGPoint(x: frame.midX, y: frame.midY)
            let dx = center.x - point.x
            let dy = center.y - point.y
            let d2 = dx*dx + dy*dy
            if best == nil || d2 < best!.1 { best = (id, d2) }
        }
        return best?.0
    }
}
