import SwiftUI

/// Asks for the carer PIN before a vocabulary change. Communication never waits
/// on this; only the change that asked for it does.
struct EditingAccessUnlockSheet: View {
    @ObservedObject var model: IosViewModel
    let onUnlocked: () -> Void
    let onCancel: () -> Void

    var body: some View {
        EditingAccessPinSheet(
            model: model,
            mode: .unlock,
            onSuccess: onUnlocked,
            onCancel: onCancel
        )
    }
}
