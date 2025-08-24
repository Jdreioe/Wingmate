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

struct ItemFramePref: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

struct WiggleEffect: ViewModifier {
    var active: Bool
    @State private var phase: Double = 0
    func body(content: Content) -> some View {
        content
            .rotationEffect(.degrees(active ? sin(phase) * 2.0 : 0))
            .scaleEffect(active ? 0.98 : 1.0)
            .onAppear { if active { start() } }
            .onChange(of: active) { a, _ in if a { start() } }
    }
    private func start() {
        withAnimation(.linear(duration: 0.35).repeatForever(autoreverses: true)) { phase = .pi * 2 }
    }
}
