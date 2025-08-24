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

    var body: some View {
        let base = ZStack {
            PhraseItemView(
                model: model,
                phrase: phrase,
                recorder: recorder,
                recordingForPhraseId: $recordingForPhraseId,
                onEdit: onEdit,
                requestMic: requestMic,
                onDelete: onDelete,
                wiggle: wiggleMode
            )
            .opacity(draggingId == phrase.id ? 0.95 : 1)
            // When in wiggle mode, disable inner buttons' hit testing so drag works smoothly
            .allowsHitTesting(!wiggleMode)
        }
        .background(
            GeometryReader { proxy in
                Color.clear.preference(key: ItemFramePref.self, value: [phrase.id: proxy.frame(in: .named("gridSpace"))])
            }
        )
        .offset(draggingId == phrase.id ? draggingOffset : .zero)
        .zIndex(draggingId == phrase.id ? 1 : 0)
        .contentShape(Rectangle())
    // Long-press now reserved for context menu; wiggle mode is toggled via toolbar

        Group {
            if wiggleMode {
                base.contentShape(Rectangle()).gesture(
                    DragGesture()
                        .onChanged { value in
                            guard draggingId == nil || draggingId == phrase.id else { return }
                            draggingId = phrase.id
                            draggingOffset = value.translation
                            if dragStartFrame == nil { dragStartFrame = itemFrames[phrase.id] }
                            guard let start = dragStartFrame else { return }
                            let currentCenter = CGPoint(x: start.midX + value.translation.width, y: start.midY + value.translation.height)
                            if let targetId = nearestId(to: currentCenter, excluding: phrase.id),
                               let from = gridLocal.firstIndex(where: { $0.id == phrase.id }),
                               let to = gridLocal.firstIndex(where: { $0.id == targetId }), from != to {
                                withAnimation(.easeInOut(duration: 0.15)) {
                                    let item = gridLocal.remove(at: from)
                                    gridLocal.insert(item, at: to)
                                }
                            }
                        }
                        .onEnded { _ in
                            guard let movingId = draggingId else { return }
                            let localTo = gridLocal.firstIndex(where: { $0.id == movingId }) ?? 0
                            commitMove(movingId, localTo)
                            draggingId = nil
                            draggingOffset = .zero
                            dragStartFrame = nil
                        }
                )
            } else {
                base
            }
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
