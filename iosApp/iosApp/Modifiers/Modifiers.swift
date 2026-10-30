import SwiftUI

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:(a, r, g, b) = (255, 0, 0, 0)
        }
        self.init(.sRGB, red: Double(r)/255, green: Double(g)/255, blue: Double(b)/255, opacity: Double(a)/255)
    }
}

/// Touch-down commits once at first contact. Moving afterwards cannot undo speech.
/// The normal Button activation remains available to VoiceOver and Switch Control.
struct CommunicationButtonStyle: PrimitiveButtonStyle {
    var touchDown: Bool

    func makeBody(configuration: Configuration) -> some View {
        CommunicationButtonBody(configuration: configuration, touchDown: touchDown)
    }
}

private struct CommunicationButtonBody: View {
    let configuration: PrimitiveButtonStyleConfiguration
    let touchDown: Bool
    @Environment(\.isEnabled) private var enabled
    @GestureState private var contacted = false

    @State private var fired = false

    var body: some View {
        if touchDown {
            configuration.label
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .updating($contacted) { _, state, _ in state = true }
                        .onChanged { _ in
                            guard enabled, !fired else { return }
                            fired = true
                            configuration.trigger()
                        }
                        .onEnded { _ in fired = false }
                )
                .onChange(of: contacted) { _, down in if !down { fired = false } }
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { if enabled { configuration.trigger() } }
        } else {
            Button(action: configuration.trigger) { configuration.label }
                .buttonStyle(.plain)
        }
    }
}
