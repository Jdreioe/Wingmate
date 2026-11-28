import SwiftUI
import Shared

/// Controls when Wingmate mirrors the active Message to an outward or external partner display.
enum PartnerDisplayMode: String, CaseIterable, Identifiable {
    case off
    case whenSpeaking
    case whileCommunicating

    var id: String { rawValue }

    var titleKey: LocalizedStringKey {
        switch self {
        case .off: "settings.partner_display.off"
        case .whenSpeaking: "settings.partner_display.when_speaking"
        case .whileCommunicating: "settings.partner_display.while_communicating"
        }
    }
}

/// Physical hinge posture abstracted from iOS 27.1 `DeviceHinge.Status` for testing and cross-OS support.
enum DuoHingePosture: Equatable {
    case unavailable
    case closed
    case partiallyOpen
    case fullyOpen
}

/// Pure policy deciding whether the communication partner's display should currently be lit.
/// Keeps setup, settings, PIN entry, screen editing, rest mode, and closed-phone usage private.
struct PartnerDisplayPolicy {
    static func shouldShow(
        mode: PartnerDisplayMode,
        inWelcomeFlow: Bool,
        isConfiguringOrUnlocking: Bool,
        isEditingScreen: Bool,
        inputIsPaused: Bool,
        hingePosture: DuoHingePosture,
        messageText: String,
        hasMessageParts: Bool,
        isSpeakingOrActive: Bool,
        isFullscreenMessage: Bool
    ) -> Bool {
        guard mode != .off else { return false }
        // Never expose onboarding, settings, credentials, PINs, or board editing to a listener.
        guard !inWelcomeFlow, !isConfiguringOrUnlocking, !isEditingScreen, !inputIsPaused else {
            return false
        }
        // When folded closed on iPhone Duo, the Communicator is using the outer screen themselves.
        guard hingePosture != .closed else { return false }

        let hasContent = !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || hasMessageParts
        switch mode {
        case .off:
            return false
        case .whenSpeaking:
            return isSpeakingOrActive || (isFullscreenMessage && hasContent)
        case .whileCommunicating:
            return hasContent || isSpeakingOrActive
        }
    }
}

/// Non-interactive, high-legibility communication display presented to the listener on an
/// outward / external secondary display via iOS 27's `ExternalNonInteractiveAccessory`.
struct PartnerDisplayView: View {
    @ObservedObject var model: IosViewModel

    private var messageText: String {
        let trimmedInput = model.communication.input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedInput.isEmpty { return trimmedInput }
        return model.communication.messageParts
            .map { $0.displayText.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private var symbolParts: [(id: String, title: String, imageUrl: String)] {
        guard model.boardModeEnabled else { return [] }
        return model.communication.messageParts.enumerated().compactMap { index, part in
            let look: (label: String, imageUrl: String?)?
            if let source = part.source as? Shared.MessagePartSourceScreenButton {
                look = model.communication.screenButtonLooks["\(source.pageId)|\(source.buttonId)"]
            } else {
                look = nil
            }
            guard let url = look?.imageUrl, !url.isEmpty else { return nil }
            let title = look?.label.isEmpty == false ? look!.label : part.displayText
            return (id: "\(index)-\(part.displayText)", title: title, imageUrl: url)
        }
    }

    private var isSpeaking: Bool {
        model.communication.playback.name == "Playing" || model.communication.playback.name == "Preparing"
    }

    var body: some View {
        GeometryReader { proxy in
            let isCompact = proxy.size.width < 500 || proxy.size.height < 420
            let fontSize: CGFloat = isCompact ? 42 : 60

            VStack(spacing: isCompact ? 16 : 24) {
                Spacer(minLength: 0)

                if !messageText.isEmpty {
                    Text(messageText)
                        .font(.system(size: fontSize, weight: .semibold, design: .rounded))
                        .multilineTextAlignment(.center)
                        .lineLimit(nil)
                        .minimumScaleFactor(0.35)
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, isCompact ? 20 : 40)
                }

                if !symbolParts.isEmpty {
                    HStack(spacing: 12) {
                        ForEach(symbolParts.prefix(6), id: \.id) { item in
                            if let url = URL(string: item.imageUrl) {
                                VStack(spacing: 4) {
                                    AsyncImage(url: url) { phase in
                                        if case .success(let image) = phase {
                                            image.resizable().scaledToFit()
                                        }
                                    }
                                    .frame(width: isCompact ? 48 : 68, height: isCompact ? 48 : 68)
                                    Text(item.title)
                                        .font(.footnote.weight(.medium))
                                        .lineLimit(1)
                                        .foregroundStyle(Color.white.opacity(0.8))
                                }
                                .padding(8)
                                .background(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .fill(Color.white.opacity(0.12))
                                )
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }

                Spacer(minLength: 0)

                if isSpeaking {
                    HStack(spacing: 8) {
                        Image(systemName: "waveform")
                            .symbolEffect(.variableColor.iterative, isActive: true)
                        Text("partner_display.speaking")
                            .font(.headline)
                    }
                    .foregroundStyle(Color.accentColor)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(Color.accentColor.opacity(0.22)))
                    .padding(.bottom, 16)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.ignoresSafeArea())
        }
        .preferredColorScheme(.dark)
    }
}

/// Attaches the iOS 27 `ExternalNonInteractiveAccessory` scene accessory and observes
/// iOS 27.1 `DeviceHinge` posture so Wingmate presents partner-facing text only when safe.
struct PartnerDisplayAccessoryModifier: ViewModifier {
    @ObservedObject var model: IosViewModel
    let isEnabled: Bool
    @Binding var isAvailable: Bool
    @Binding var hingePosture: DuoHingePosture

    func body(content: Content) -> some View {
        let withHinge = applyHingeObserver(to: content)
        #if compiler(>=6.4)
        if #available(iOS 27.0, *) {
            withHinge.sceneAccessory {
                ExternalNonInteractiveAccessory(
                    isEnabled: Binding(get: { isEnabled }, set: { _ in })
                ) {
                    PartnerDisplayView(model: model)
                }
                .onAvailabilityChange { available in
                    isAvailable = available
                }
            }
        } else {
            withHinge
        }
        #else
        withHinge
        #endif
    }

    @ViewBuilder
    private func applyHingeObserver(to content: Content) -> some View {
        #if compiler(>=6.4.1)
        if #available(iOS 27.1, *) {
            content.onHingeChange { _, newContext in
                guard let hinge = newContext.hinge else {
                    hingePosture = .unavailable
                    return
                }
                switch hinge.status {
                case .closed: hingePosture = .closed
                case .partiallyOpen: hingePosture = .partiallyOpen
                case .fullyOpen: hingePosture = .fullyOpen
                default: hingePosture = .unavailable
                }
            }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

extension View {
    /// Connects Wingmate's communication partner display accessory on iOS 27+ devices.
    func partnerDisplayAccessory(
        model: IosViewModel,
        isEnabled: Bool,
        isAvailable: Binding<Bool>,
        hingePosture: Binding<DuoHingePosture>
    ) -> some View {
        modifier(
            PartnerDisplayAccessoryModifier(
                model: model,
                isEnabled: isEnabled,
                isAvailable: isAvailable,
                hingePosture: hingePosture
            )
        )
    }

    /// Keeps iPhone Duo's system bar horizontal instead of reserving a vertical side column,
    /// preserving full horizontal width for AAC button grids and the typing composer.
    @ViewBuilder
    func preferHorizontalToolbarOnDuo() -> some View {
        #if compiler(>=6.4.1)
        if #available(iOS 27.1, *) {
            self.toolbarVerticalBehavior(.disabled)
        } else {
            self
        }
        #else
        self
        #endif
    }
}
