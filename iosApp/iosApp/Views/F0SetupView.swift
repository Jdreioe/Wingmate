import SwiftUI
import Shared

let F0_PORTAL_TEMPLATE_URL = "https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjdreioe%2Fwingmate%2Fmain%2Finfra%2Fazure-user-f0%2Fazuredeploy.json"
let AZURE_SPEECH_RESOURCES_URL = "https://portal.azure.com/#view/HubsExtension/BrowseResource/resourceType/Microsoft.CognitiveServices%2Faccounts"

enum F0Step {
    case welcome
    case portal
    case credentials
    case success
}

struct F0SetupView: View {
    let onDone: () -> Void
    let onBack: () -> Void

    @State private var step: F0Step = .welcome
    @State private var endpoint: String = ""
    @State private var subscriptionKey: String = ""
    @State private var saveError: String? = nil
    @State private var saving: Bool = false
    private let bridge = KoinBridge()

    var body: some View {
        VStack(spacing: 0) {
            // Header bar
            HStack {
                Button(action: {
                    switch step {
                    case .welcome:
                        onBack()
                    case .portal, .credentials:
                        step = .welcome
                    case .success:
                        onDone()
                    }
                }) {
                    Text(step == .welcome ? NSLocalizedString("common.cancel", comment: "") : NSLocalizedString("welcome_flow.back", comment: ""))
                        .font(.headline)
                        .foregroundColor(.blue)
                }

                Spacer()

                Text(NSLocalizedString("azure_setup.title", comment: ""))
                    .font(.headline)
                    .fontWeight(.semibold)

                Spacer()

                // Invisible spacer to balance navigation header
                Text(NSLocalizedString("common.cancel", comment: ""))
                    .font(.headline)
                    .opacity(0)
            }
            .padding(.top, 16)
            .padding(.bottom, 24)

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    switch step {
                    case .welcome:
                        welcomeStepView
                    case .portal:
                        portalStepView
                    case .credentials:
                        credentialsStepView
                    case .success:
                        successStepView
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var welcomeStepView: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(NSLocalizedString("azure_setup.free_tier.title", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            Text(NSLocalizedString("azure_setup.free_tier.desc", comment: ""))
                .font(.body)
                .foregroundColor(.secondary)

            // Portal Card (Recommended)
            Button(action: openPortal) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "cloud.fill")
                            .font(.title2)
                            .foregroundColor(.blue)
                        Text(NSLocalizedString("azure_setup.portal.title", comment: ""))
                            .font(.headline)
                            .foregroundColor(.primary)
                    }
                    Text(NSLocalizedString("azure_setup.portal.desc", comment: ""))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.leading)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.blue.opacity(0.1))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.blue.opacity(0.3), lineWidth: 1)
                        )
                )
            }
            .buttonStyle(PlainButtonStyle())

            // Existing Key Card
            Button(action: { step = .credentials }) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "key.fill")
                            .font(.title2)
                            .foregroundColor(.purple)
                        Text(NSLocalizedString("azure_setup.existing_key.title", comment: ""))
                            .font(.headline)
                            .foregroundColor(.primary)
                    }
                    Text(NSLocalizedString("azure_setup.existing_key.desc", comment: ""))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.leading)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color(.secondarySystemBackground))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color(.separator), lineWidth: 1)
                        )
                )
            }
            .buttonStyle(PlainButtonStyle())
        }
    }

    @ViewBuilder
    private var portalStepView: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(NSLocalizedString("azure_setup.create.title", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            Text(NSLocalizedString("azure_setup.in_portal", comment: ""))
                .font(.headline)

            Text(NSLocalizedString("azure_setup.steps", comment: ""))
                .font(.body)
                .foregroundColor(.secondary)

            VStack(spacing: 12) {
                Button(action: openPortal) {
                    HStack {
                        Image(systemName: "safari.fill")
                        Text(NSLocalizedString("azure_setup.open_portal", comment: ""))
                    }
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.blue)
                    .cornerRadius(12)
                }

                Button(action: openSpeechResources) {
                    HStack {
                        Image(systemName: "folder.fill")
                        Text(NSLocalizedString("azure_setup.open_resources", comment: ""))
                    }
                    .font(.headline)
                    .foregroundColor(.blue)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.blue, lineWidth: 1)
                    )
                }

                Button(action: { step = .credentials }) {
                    Text(NSLocalizedString("azure_setup.have_credentials", comment: ""))
                        .font(.headline)
                        .foregroundColor(.primary)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color(.separator), lineWidth: 1)
                        )
                }
            }
            .padding(.top, 8)

            Text(NSLocalizedString("azure_setup.limit", comment: ""))
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private var credentialsStepView: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(NSLocalizedString("azure_setup.credentials.title", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            Text(NSLocalizedString("azure_setup.credentials.desc", comment: ""))
                .font(.body)
                .foregroundColor(.secondary)

            VStack(alignment: .leading, spacing: 12) {
                Text(NSLocalizedString("azure_setup.endpoint.label", comment: ""))
                    .font(.subheadline)
                    .fontWeight(.medium)

                TextField(NSLocalizedString("azure.endpoint.placeholder", comment: ""), text: $endpoint)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                Text(NSLocalizedString("azure_setup.key.label", comment: ""))
                    .font(.subheadline)
                    .fontWeight(.medium)

                SecureField(NSLocalizedString("azure.key.placeholder", comment: ""), text: $subscriptionKey)
                    .textFieldStyle(RoundedBorderTextFieldStyle())

                if let err = saveError {
                    Text(err)
                        .font(.caption)
                        .foregroundColor(.red)
                }
            }

            Button(action: saveCredentials) {
                HStack {
                    if saving {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            .padding(.trailing, 8)
                    }
                    Text(NSLocalizedString("azure_setup.save_continue", comment: ""))
                }
                .font(.headline)
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding()
                .background(saving || endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || subscriptionKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Color.gray : Color.blue)
                .cornerRadius(12)
            }
            .disabled(saving || endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || subscriptionKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .padding(.top, 8)
        }
    }

    @ViewBuilder
    private var successStepView: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 40)

            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 80))
                .foregroundColor(.green)

            Text(NSLocalizedString("azure_setup.complete.title", comment: ""))
                .font(.title)
                .fontWeight(.bold)

            Text(NSLocalizedString("azure_setup.complete.desc", comment: ""))
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal)

            Spacer(minLength: 40)

            Button(action: onDone) {
                Text(NSLocalizedString("common.continue", comment: ""))
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.blue)
                    .cornerRadius(12)
            }
        }
    }

    private func openPortal() {
        if let url = URL(string: F0_PORTAL_TEMPLATE_URL) {
            UIApplication.shared.open(url)
        }
        step = .portal
    }

    private func openSpeechResources() {
        if let url = URL(string: AZURE_SPEECH_RESOURCES_URL) {
            UIApplication.shared.open(url)
        }
    }

    private func saveCredentials() {
        let ep = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = subscriptionKey.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !ep.isEmpty else {
            saveError = NSLocalizedString("azure_setup.error.endpoint", comment: "")
            return
        }
        guard !key.isEmpty else {
            saveError = NSLocalizedString("azure_setup.error.key", comment: "")
            return
        }

        saving = true
        saveError = nil

        Task {
            do {
                try await bridge.saveAzureSpeechConfig(endpoint: ep, subscriptionKey: key)
                await MainActor.run {
                    saving = false
                    step = .success
                }
            } catch {
                await MainActor.run {
                    saving = false
                    saveError = String(format: NSLocalizedString("azure_setup.error.save", comment: ""), error.localizedDescription)
                }
            }
        }
    }
}
