import SwiftUI

/// Asks for the Editing access code before a vocabulary change. Communication
/// never waits on this; only the change that asked for it does.
struct EditingAccessUnlockSheet: View {
    @ObservedObject var model: IosViewModel
    let onUnlocked: () -> Void
    let onCancel: () -> Void

    @State private var code = ""
    @State private var incorrect = false

    var body: some View {
        NavigationStack {
            Form {
                SecureField("editing_access.current_code", text: $code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                if incorrect {
                    Text("editing_access.incorrect").foregroundStyle(.red)
                }
            }
            .navigationTitle(Text("editing_access.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.ok") {
                        Task {
                            if await model.unlockEditingAccess(code) {
                                onUnlocked()
                            } else {
                                incorrect = true
                                code = ""
                            }
                        }
                    }
                    .disabled(code.isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}
