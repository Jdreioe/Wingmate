import UIKit

/// Haptic feedback for access interactions, mirroring Android's `AccessHaptic`.
enum AccessHaptic {
    case confirm
    case reject
    case tick
}

@MainActor
enum AccessHaptics {
    private static let impactGenerator = UIImpactFeedbackGenerator(style: .medium)
    private static let notificationGenerator = UINotificationFeedbackGenerator()
    private static let selectionGenerator = UISelectionFeedbackGenerator()

    static func perform(_ haptic: AccessHaptic) {
        switch haptic {
        case .confirm:
            impactGenerator.prepare()
            impactGenerator.impactOccurred()
        case .reject:
            notificationGenerator.prepare()
            notificationGenerator.notificationOccurred(.warning)
        case .tick:
            selectionGenerator.prepare()
            selectionGenerator.selectionChanged()
        }
    }
}
