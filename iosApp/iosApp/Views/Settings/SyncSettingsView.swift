import SwiftUI
import Shared
import UniformTypeIdentifiers

/// Settings → Sync: Google Drive, OneDrive, or an OS folder whose `Wingmate` folder syncs Screens.
struct SyncSettingsView: View {
    @ObservedObject var model: IosViewModel
    @State private var connections: [SyncConnection] = []
    @State private var cloudProviders: [String] = []
    @State private var busy = false
    @State private var statusMessage: String?
    @State private var showFolderImporter = false
    @State private var showUnlockSheet = false
    @State private var pendingAction: (() -> Void)?

    private var hasGoogleDrive: Bool { connections.contains { $0.kind == .googleDrive } }
    private var hasOneDrive: Bool { connections.contains { $0.kind == .onedrive } }

    var body: some View {
        Form {
            Section {
                if connections.isEmpty {
                    Text("settings.sync.empty")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(connections, id: \.id) { connection in
                        HStack(spacing: 12) {
                            Image(systemName: icon(for: connection.kind))
                                .foregroundStyle(Color.accentColor)
                                .frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(title(for: connection))
                                    .font(.body)
                                Text(subtitle(for: connection))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("remote_screen.disconnect", role: .destructive) {
                                runAuthorized { disconnect(connection.id) }
                            }
                            .buttonStyle(.borderless)
                            .disabled(busy)
                        }
                    }
                }
            } header: {
                Text("settings.sync.connections")
            } footer: {
                Text("settings.sync.folder_footer")
            }

            Section("settings.sync.add") {
                if cloudProviders.contains("google_drive") && !hasGoogleDrive {
                    Button {
                        runAuthorized { connectCloud("google_drive") }
                    } label: {
                        Label("remote_screen.provider.google_drive", systemImage: "icloud.and.arrow.up")
                    }
                    .disabled(busy)
                }
                if cloudProviders.contains("onedrive") && !hasOneDrive {
                    Button {
                        runAuthorized { connectCloud("onedrive") }
                    } label: {
                        Label("remote_screen.provider.onedrive", systemImage: "icloud.and.arrow.up")
                    }
                    .disabled(busy)
                }
                Button {
                    runAuthorized { showFolderImporter = true }
                } label: {
                    Label("settings.sync.add_folder", systemImage: "folder.badge.plus")
                }
                .disabled(busy)
            }

            Section {
                Button {
                    syncNow()
                } label: {
                    if busy {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("remote_screen.working")
                        }
                    } else {
                        Label("remote_screen.sync_now", systemImage: "arrow.triangle.2.circlepath")
                    }
                }
                .disabled(busy)

                if let statusMessage, !statusMessage.isEmpty {
                    Text(statusMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } footer: {
                Text("settings.sync.icloud_note")
            }
        }
        .navigationTitle(Text("settings.category.sync"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .fileImporter(
            isPresented: $showFolderImporter,
            allowedContentTypes: [.folder],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                connectFolder(url)
            case .failure(let error):
                statusMessage = error.localizedDescription
            }
        }
        .sheet(isPresented: $showUnlockSheet, onDismiss: {
            if let action = pendingAction {
                pendingAction = nil
                Task {
                    if await model.editingIsAuthorized() {
                        action()
                    }
                }
            }
        }) {
            EditingAccessPinSheet(model: model, mode: .unlock, onSuccess: {
                showUnlockSheet = false
            }, onCancel: {
                pendingAction = nil
                showUnlockSheet = false
            })
        }
    }

    private func icon(for kind: RemoteStorageKind) -> String {
        switch kind {
        case .googleDrive, .onedrive: "icloud"
        case .linkedFile: "folder"
        case .icloudShare: "person.2"
        default: "icloud"
        }
    }

    private func title(for connection: SyncConnection) -> String {
        switch connection.kind {
        case .googleDrive: NSLocalizedString("remote_screen.provider.google_drive", comment: "")
        case .onedrive: NSLocalizedString("remote_screen.provider.onedrive", comment: "")
        case .linkedFile: connection.accountLabel.isEmpty ? NSLocalizedString("settings.sync.folder_label", comment: "") : connection.accountLabel
        default: connection.folderName
        }
    }

    private func subtitle(for connection: SyncConnection) -> String {
        let folder = String(format: NSLocalizedString("settings.sync.wingmate_folder", comment: ""), connection.folderName)
        if connection.kind != .linkedFile && !connection.accountLabel.isEmpty {
            return "\(connection.accountLabel) · \(folder)"
        }
        return folder
    }

    private func reload() async {
        let actions = IosDiBridge().remoteScreenActions()
        cloudProviders = actions.cloudDriveProviders
        connections = (try? await actions.listSyncConnections()) ?? []
    }

    private func runAuthorized(_ action: @escaping () -> Void) {
        Task {
            if await model.editingIsAuthorized() {
                action()
            } else {
                pendingAction = action
                showUnlockSheet = true
            }
        }
    }

    private func connectCloud(_ provider: String) {
        busy = true
        statusMessage = nil
        Task {
            defer { busy = false }
            let result = try? await IosDiBridge().remoteScreenActions().connectCloudSync(provider: provider)
            statusMessage = result?.message
            await reload()
            await model.loadBoardSets()
        }
    }

    private func connectFolder(_ url: URL) {
        busy = true
        statusMessage = nil
        Task {
            defer { busy = false }
            let handle = IosDiBridge().registerSyncFolder(
                urlString: url.absoluteString,
                displayName: url.lastPathComponent
            )
            let result = try? await IosDiBridge().remoteScreenActions().connectFolderSync(
                folderLocation: handle.location,
                folderName: handle.displayName
            )
            statusMessage = result?.message
            await reload()
            await model.loadBoardSets()
        }
    }

    private func disconnect(_ id: String) {
        busy = true
        statusMessage = nil
        Task {
            defer { busy = false }
            let result = try? await IosDiBridge().remoteScreenActions().disconnectSyncConnection(connectionId: id)
            statusMessage = result?.message
            await reload()
            await model.loadBoardSets()
        }
    }

    private func syncNow() {
        busy = true
        statusMessage = nil
        Task {
            defer { busy = false }
            let result = try? await IosDiBridge().remoteScreenActions().syncNowAll()
            statusMessage = result?.message
            await reload()
            await model.loadBoardSets()
        }
    }
}
