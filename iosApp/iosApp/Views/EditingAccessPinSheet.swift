import SwiftUI

/// Carer PIN for editing. The iPad belongs to the person who communicates, so this
/// never uses Face ID, Touch ID, or the device passcode.
enum EditingAccessPinMode: String, Identifiable {
    case unlock
    case create
    case change
    case disable

    var id: String { rawValue }
}

struct EditingAccessPinSheet: View {
    @ObservedObject var editingAccess: EditingAccessModel
    var mode: EditingAccessPinMode
    var onSuccess: () -> Void
    var onCancel: () -> Void

    @State private var step = 0
    @State private var entry = ""
    @State private var firstCode = ""
    @State private var errorKey: LocalizedStringKey?
    @State private var working = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(prompt)
                    .font(.title3)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                pinDots
                if let errorKey {
                    Text(errorKey)
                        .font(.body)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                }
                keypad
            }
            .padding(20)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(Text(titleKey))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel", action: onCancel)
                        .disabled(working)
                }
            }
        }
        .presentationDetents([.large])
        .presentationBackground(Color(.systemBackground))
    }

    private var titleKey: LocalizedStringKey {
        switch mode {
        case .unlock: "editing_access.unlock_title"
        case .create: "editing_access.setup_title"
        case .change: "editing_access.change"
        case .disable: "editing_access.disable"
        }
    }

    private var prompt: LocalizedStringKey {
        switch mode {
        case .unlock, .disable:
            return "editing_access.current_code"
        case .create:
            return step == 0 ? "editing_access.new_code" : "editing_access.confirm_code"
        case .change:
            if step == 0 { return "editing_access.current_code" }
            return step == 1 ? "editing_access.new_code" : "editing_access.confirm_code"
        }
    }

    private var pinDots: some View {
        HStack(spacing: 10) {
            ForEach(0..<8, id: \.self) { index in
                Circle()
                    .fill(index < entry.count ? Color.primary : Color.clear)
                    .overlay(Circle().stroke(Color.primary, lineWidth: 2))
                    .frame(width: dotSize, height: dotSize)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(String(
            format: NSLocalizedString("editing_access.entered", comment: ""),
            entry.count
        )))
    }

    private var dotSize: CGFloat {
        dynamicTypeSize.isAccessibilitySize ? 22 : 16
    }

    private var keypad: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 12) {
            ForEach(1...9, id: \.self) { digit in
                digitButton("\(digit)")
            }
            keyButton("editing_access.delete_digit", systemImage: "delete.left") {
                if !entry.isEmpty { entry.removeLast() }
                errorKey = nil
            }
            digitButton("0")
            keyButton("common.ok", systemImage: "checkmark") {
                Task { await submit() }
            }
            .disabled(working || entry.count < 4)
        }
    }

    private func digitButton(_ digit: String) -> some View {
        Button {
            guard entry.count < 8, !working else { return }
            entry.append(digit)
            errorKey = nil
        } label: {
            Text(digit)
                .font(.largeTitle)
                .frame(maxWidth: .infinity, minHeight: keyHeight)
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(Text(digit))
    }

    private func keyButton(_ key: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title)
                .frame(maxWidth: .infinity, minHeight: keyHeight)
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(Text(key))
    }

    private var keyHeight: CGFloat {
        dynamicTypeSize.isAccessibilitySize ? 84 : 68
    }

    @MainActor
    private func submit() async {
        guard !working else { return }
        if editingAccess.isThrottled() && (mode == .unlock || mode == .disable || (mode == .change && step == 0)) {
            errorKey = "editing_access.wait"
            return
        }
        working = true
        defer { working = false }
        switch mode {
        case .unlock:
            if await editingAccess.unlock(code: entry) {
                onSuccess()
            } else {
                fail()
            }
        case .disable:
            if await editingAccess.disable(code: entry) {
                onSuccess()
            } else {
                fail()
            }
        case .create:
            await advanceNewCode(configure: true)
        case .change:
            if step == 0 {
                if await editingAccess.unlock(code: entry) {
                    entry = ""
                    step = 1
                    errorKey = nil
                } else {
                    fail()
                }
            } else {
                await advanceNewCode(configure: true)
            }
        }
    }

    @MainActor
    private func advanceNewCode(configure: Bool) async {
        let creating = mode == .create || (mode == .change && step >= 1)
        let confirming = (mode == .create && step == 1) || (mode == .change && step == 2)
        if !confirming && creating {
            firstCode = entry
            entry = ""
            step += 1
            errorKey = nil
            return
        }
        guard entry == firstCode else {
            entry = ""
            errorKey = "editing_access.mismatch"
            return
        }
        if configure, await editingAccess.configure(code: entry) {
            onSuccess()
        } else {
            fail()
        }
    }

    private func fail() {
        entry = ""
        if let failureKey = editingAccess.lastFailureKey {
            errorKey = LocalizedStringKey(failureKey)
        } else {
            errorKey = editingAccess.isThrottled()
                ? "editing_access.wait"
                : "editing_access.incorrect"
        }
    }
}
