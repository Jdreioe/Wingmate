import SwiftUI

/// Asks for the carer PIN before a vocabulary change. Communication never waits
/// on this; only the change that asked for it does.
struct EditingAccessUnlockSheet: View {
    @ObservedObject var editingAccess: EditingAccessModel
    let onUnlocked: () -> Void
    let onCancel: () -> Void

    var body: some View {
        EditingAccessPinSheet(
            editingAccess: editingAccess,
            mode: .unlock,
            onSuccess: onUnlocked,
            onCancel: onCancel
        )
    }
}
