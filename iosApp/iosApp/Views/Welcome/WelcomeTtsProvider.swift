import Foundation

enum WelcomeTtsProvider: String, CaseIterable, Identifiable {
    case system = "SYSTEM"
    case azure = "AZURE_USER_RESOURCE"
    case google = "GOOGLE_CLOUD"
    case elevenLabs = "ELEVENLABS"

    var id: String { rawValue }
    var engineId: String { rawValue }
    var requiresSetup: Bool { self != .system }

    static func from(engineId: String?) -> WelcomeTtsProvider {
        switch engineId {
        case "AZURE_USER_RESOURCE", "AZURE_MANAGED":
            return .azure
        case "GOOGLE_CLOUD":
            return .google
        case "ELEVENLABS":
            return .elevenLabs
        default:
            return .system
        }
    }

    func matches(engineId: String?) -> Bool {
        switch self {
        case .azure:
            return engineId == "AZURE_USER_RESOURCE" || engineId == "AZURE_MANAGED"
        default:
            return engineId == rawValue
        }
    }

    @MainActor
    func isConfigured(in model: IosViewModel) -> Bool {
        switch self {
        case .system:
            return true
        case .azure:
            return model.azureConfigured
        case .google:
            return model.googleConfigured
        case .elevenLabs:
            return model.elevenLabsConfigured
        }
    }

    var icon: String {
        switch self {
        case .system:
            return "speaker.wave.2.fill"
        case .azure:
            return "cloud.fill"
        case .google:
            return "cloud.sun.fill"
        case .elevenLabs:
            return "waveform.circle.fill"
        }
    }

    var title: String {
        switch self {
        case .system:
            return NSLocalizedString("welcome_flow.system_tts.title", comment: "")
        case .azure:
            return NSLocalizedString("welcome_flow.azure_tts.title", comment: "")
        case .google:
            return NSLocalizedString("welcome_flow.google_tts.title", comment: "")
        case .elevenLabs:
            return NSLocalizedString("welcome_flow.elevenlabs_tts.title", comment: "")
        }
    }

    var subtitle: String {
        switch self {
        case .system:
            return NSLocalizedString("welcome_flow.system_tts.subtitle", comment: "")
        case .azure:
            return NSLocalizedString("welcome_flow.azure_tts.subtitle", comment: "")
        case .google:
            return NSLocalizedString("welcome_flow.google_tts.subtitle", comment: "")
        case .elevenLabs:
            return NSLocalizedString("welcome_flow.elevenlabs_tts.subtitle", comment: "")
        }
    }

    var pros: [String] {
        switch self {
        case .system:
            return [
                NSLocalizedString("welcome_flow.system_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.system_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.system_tts.pro3", comment: "")
            ]
        case .azure:
            return [
                NSLocalizedString("welcome_flow.azure_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.pro3", comment: "")
            ]
        case .google:
            return [
                NSLocalizedString("welcome_flow.google_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.pro3", comment: "")
            ]
        case .elevenLabs:
            return [
                NSLocalizedString("welcome_flow.elevenlabs_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.pro3", comment: "")
            ]
        }
    }

    var cons: [String] {
        switch self {
        case .system:
            return [
                NSLocalizedString("welcome_flow.system_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.system_tts.con2", comment: "")
            ]
        case .azure:
            return [
                NSLocalizedString("welcome_flow.azure_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.con2", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.con3", comment: "")
            ]
        case .google:
            return [
                NSLocalizedString("welcome_flow.google_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.con2", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.con3", comment: "")
            ]
        case .elevenLabs:
            return [
                NSLocalizedString("welcome_flow.elevenlabs_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.con2", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.con3", comment: "")
            ]
        }
    }

    var voicesSubtitle: String {
        switch self {
        case .system:
            return NSLocalizedString("welcome_flow.system_tts_info", comment: "")
        case .azure:
            return NSLocalizedString("welcome_flow.azure_voices_subtitle", comment: "")
        case .google:
            return NSLocalizedString("welcome_flow.google_voices_subtitle", comment: "")
        case .elevenLabs:
            return NSLocalizedString("welcome_flow.elevenlabs_voices_subtitle", comment: "")
        }
    }

    var configureTitle: String? {
        switch self {
        case .system:
            return nil
        case .azure:
            return NSLocalizedString("settings.speech.azure.configure", comment: "")
        case .google:
            return NSLocalizedString("settings.speech.google.configure", comment: "")
        case .elevenLabs:
            return NSLocalizedString("elevenlabs.setup", comment: "")
        }
    }
}
