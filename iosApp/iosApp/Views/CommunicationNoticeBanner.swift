import SwiftUI
import Shared

/// Shows the Communication session's notice above both workspaces: a storage
/// failure with Retry, a playback failure, or the device-voice fallback.
struct CommunicationNoticeBanner: View {
    @ObservedObject var model: IosViewModel

    var body: some View {
        if let notice = model.sessionNotice {
            let storageFailed = notice.name == "StorageFailed"
            // The fallback still spoke the Message, so it is a notice rather than an error.
            let isError = notice.name != "SpeechFallback"
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: isError ? "exclamationmark.triangle.fill" : "info.circle.fill")
                    .foregroundStyle(isError ? Color.red : Color.accentColor)
                    .accessibilityHidden(true)
                Text(message(for: notice))
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if storageFailed {
                    Button("common.retry") { model.retrySessionStorage() }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.sessionIsSaving)
                } else {
                    Button("common.dismiss") { model.dismissSessionNotice() }
                        .buttonStyle(.bordered)
                }
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(isError ? Color.red.opacity(0.12) : Color.accentColor.opacity(0.12))
            )
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
            .onAppear {
                UIAccessibility.post(notification: .announcement, argument: message(for: notice))
            }
        }
    }

    private func message(for notice: Shared.SessionNotice) -> String {
        if notice.name == "StorageFailed" { return NSLocalizedString("communication.storage_failed", comment: "") }
        if notice.name == "PlaybackFailed" { return NSLocalizedString("communication.playback_failed", comment: "") }
        return NSLocalizedString("communication.speech_fallback", comment: "")
    }
}
