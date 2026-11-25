import SwiftUI
import Shared
import UniformTypeIdentifiers

private struct EmptyLinkedObzDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    init() {}
    init(configuration: ReadConfiguration) throws {}
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data())
    }
}

struct RemoteScreenSheet: View {
    let target: BoardSetInfo?
    var acceptedShare: AcceptedCloudKitScreenShare? = nil
    let refresh: () async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var provider = "icloud_share"
    @State private var location = ""
    @State private var displayName = ""
    @State private var ownership = ""
    @State private var ownerName = ""
    @State private var busy = false
    @State private var showFileImporter = false
    @State private var showFileExporter = false
    @State private var result: RemoteScreenResult?
    @State private var readOnly = false
    @State private var cloudProviders: [String] = []
    /// Cloud drives already signed in through Settings → Sync; they never ask to sign in again.
    @State private var connectedCloudDrives: [String] = []
    @State private var accountRef = ""
    @State private var accountMessage: String?
    @State private var cloudFiles: [RemoteFileChoice] = []
    @State private var listedCloudFiles = false

    private var isICloudLink: Bool { target?.remoteKind == "icloud_share" }
    private var isOwnerLink: Bool { target?.remoteOwnership == "owner" }
    private var isSharedLink: Bool { target?.remoteOwnership == "shared" }
    private var isCloudDrive: Bool { provider == "google_drive" || provider == "onedrive" }
    private var cloudProviderName: String {
        NSLocalizedString(provider == "google_drive" ? "remote_screen.provider.google_drive" : "remote_screen.provider.onedrive", comment: "")
    }

    private var conflict: Bool {
        result?.status == "conflict" || result?.status == "read_only_conflict" || target?.remoteConflict == true
    }

    private var ownershipSummary: String? {
        if isOwnerLink {
            return NSLocalizedString("remote_screen.owner.you", comment: "")
        }
        if isSharedLink {
            if let owner = target?.remoteOwnerName, !owner.isEmpty {
                return String(format: NSLocalizedString("remote_screen.owner.named", comment: ""), owner)
            }
            return NSLocalizedString("remote_screen.owner.shared", comment: "")
        }
        return nil
    }

    private var disconnectLabelKey: LocalizedStringKey {
        if isICloudLink && isOwnerLink { return "remote_screen.icloud.stop_sharing" }
        if isICloudLink && isSharedLink { return "remote_screen.icloud.leave_share" }
        return "remote_screen.disconnect"
    }

    var body: some View {
        NavigationStack {
            Form {
                if let remoteName = target?.remoteName {
                    Section("remote_screen.storage") {
                        Text(remoteName)
                        if let ownershipSummary {
                            Label(ownershipSummary, systemImage: isSharedLink ? "person.2.fill" : "person.crop.circle.badge.checkmark")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        if target?.remoteWritable == false {
                            Text("remote_screen.icloud.view_only")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Text("remote_screen.auto_sync_note")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        if conflict {
                            Button("remote_screen.conflict.use_remote", role: .destructive) { submit("use_remote") }
                            if target?.remoteWritable == true {
                                Button("remote_screen.conflict.keep_local", role: .destructive) { submit("keep_local") }
                            }
                            Button("remote_screen.conflict.save_copy") { submit("save_remote_as_copy") }
                        } else {
                            Button("remote_screen.sync_now") { submit("sync") }
                        }
                        if isICloudLink {
                            Button("remote_screen.icloud.manage") { manageICloudShare() }
                        }
                        Button(disconnectLabelKey, role: isICloudLink ? .destructive : nil) { submit("disconnect") }
                        if isICloudLink {
                            Text("remote_screen.icloud.disconnect_note")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                } else if result?.status != "ok" {
                    Section {
                        Picker("remote_screen.provider.label", selection: $provider) {
                            Text("remote_screen.provider.icloud_share").tag("icloud_share")
                            if cloudProviders.contains("google_drive") {
                                Text("remote_screen.provider.google_drive").tag("google_drive")
                            }
                            if cloudProviders.contains("onedrive") {
                                Text("remote_screen.provider.onedrive").tag("onedrive")
                            }
                            Text("remote_screen.provider.linked_file").tag("linked_file")
                        }
                        .onChange(of: provider) {
                            accountRef = ""
                            accountMessage = nil
                            cloudFiles = []
                            listedCloudFiles = false
                            guard acceptedShare == nil || provider != "icloud_share" else { return }
                            location = ""
                            displayName = ""
                            ownership = ""
                            ownerName = ""
                            readOnly = false
                            if connectedCloudDrives.contains(provider) { signInToCloudDrive() }
                        }
                        if isCloudDrive {
                            cloudDriveRows
                        } else if provider == "icloud_share" {
                            if let target {
                                Text("remote_screen.icloud.share_note")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                Button("remote_screen.icloud.share_action") { shareWithICloud(target) }
                            } else if let acceptedShare, location == acceptedShare.location {
                                Text(String(
                                    format: NSLocalizedString("remote_screen.icloud.accepted", comment: ""),
                                    acceptedShare.title.isEmpty ? "iCloud" : acceptedShare.title
                                ))
                                if let owner = acceptedShare.ownerName, !owner.isEmpty {
                                    Label(
                                        String(format: NSLocalizedString("remote_screen.owner.named", comment: ""), owner),
                                        systemImage: "person.2.fill"
                                    )
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                }
                                if acceptedShare.readOnly {
                                    Text("remote_screen.icloud.view_only")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Button("remote_screen.open_action") { submit("open") }
                            } else {
                                Text("remote_screen.icloud.open_hint")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } else if provider == "linked_file" {
                            Button(LocalizedStringKey(target == nil ? "remote_screen.choose_file" : "remote_screen.create_file")) {
                                if target == nil {
                                    showFileImporter = true
                                } else {
                                    showFileExporter = true
                                }
                            }
                            if !displayName.isEmpty {
                                Text(String(format: NSLocalizedString("remote_screen.selected_file", comment: ""), displayName))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Button(LocalizedStringKey(target == nil ? "remote_screen.open_action" : "remote_screen.connect")) {
                                submit(target == nil ? "open" : "connect")
                            }
                            .disabled(location.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                }
                if let result { Text(result.message) }
                if busy { ProgressView("remote_screen.working") }
            }
            .disabled(busy)
            .navigationTitle(target?.name ?? NSLocalizedString("remote_screen.open", comment: ""))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("remote_screen.close") { dismiss() }.disabled(busy)
                }
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.data],
                allowsMultipleSelection: false
            ) { picked in
                handlePickedFile(picked.map { $0.first })
            }
            .fileExporter(
                isPresented: $showFileExporter,
                document: EmptyLinkedObzDocument(),
                contentType: .data,
                defaultFilename: "\(target?.name ?? "screen").obz"
            ) { exported in
                handlePickedFile(exported.map { Optional($0) })
            }
        }
        .interactiveDismissDisabled(busy)
        .onAppear {
            cloudProviders = IosDiBridge().remoteScreenActions().cloudDriveProviders
            if target == nil, let acceptedShare {
                provider = "icloud_share"
                location = acceptedShare.location
                readOnly = acceptedShare.readOnly
                ownership = acceptedShare.isOwner ? "owner" : "shared"
                ownerName = acceptedShare.ownerName ?? ""
            }
        }
        .task {
            // Start on the drive connected in Settings → Sync and use its account right away.
            connectedCloudDrives = (try? await IosDiBridge().remoteScreenActions().connectedCloudDrives()) ?? []
            guard target?.remoteName == nil, acceptedShare == nil, let connected = connectedCloudDrives.first else { return }
            if provider == connected {
                signInToCloudDrive()
            } else {
                provider = connected
            }
        }
    }

    /// Owner flow: create the CloudKit record and share, upload this Screen, then invite people.
    private func shareWithICloud(_ target: BoardSetInfo) {
        busy = true
        Task {
            defer { busy = false }
            do {
                let prepared = try await CloudKitScreenShareService.shared.createShare(screenName: target.name)
                let response = try await perform(RemoteScreenRequest(
                    action: "connect", screenId: target.id, provider: "icloud_share", location: prepared.location,
                    displayName: "", readOnly: false, accountRef: "", ownership: "owner", ownerName: ""
                ))
                result = response
                await refresh()
                if response.status == "ok" {
                    CloudSharingPresenter.present(share: prepared.share, title: target.name) {
                        submit("disconnect")
                    }
                } else {
                    try? await CloudKitScreenShareService.shared.release(location: prepared.location)
                }
            } catch {
                result = RemoteScreenResult(status: "error", message: CloudKitScreenShareService.map(error).message)
            }
        }
    }

    /// Google Drive / OneDrive: sign in, then save this Screen as a new file or pick a file to open.
    @ViewBuilder
    private var cloudDriveRows: some View {
        if accountRef.isEmpty {
            Text(LocalizedStringKey(provider == "google_drive" ? "remote_screen.cloud.google_note" : "remote_screen.cloud.onedrive_note"))
                .font(.caption)
                .foregroundColor(.secondary)
            Button(String(format: NSLocalizedString("remote_screen.cloud.sign_in", comment: ""), cloudProviderName)) {
                signInToCloudDrive()
            }
        } else {
            if let accountMessage {
                Text(accountMessage).font(.caption).foregroundColor(.secondary)
            }
            if target != nil {
                Text("remote_screen.cloud.save_note")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Button(String(format: NSLocalizedString("remote_screen.cloud.save_action", comment: ""), cloudProviderName)) {
                    location = ""
                    ownership = "owner"
                    ownerName = ""
                    submit("connect")
                }
            } else if listedCloudFiles && cloudFiles.isEmpty {
                Text("remote_screen.cloud.no_files")
                    .font(.caption)
                    .foregroundColor(.secondary)
            } else {
                ForEach(cloudFiles, id: \.location) { file in
                    Button {
                        location = file.location
                        displayName = file.name
                        if let owned = file.ownedByMe?.boolValue {
                            ownership = owned ? "owner" : "shared"
                        } else {
                            ownership = ""
                        }
                        ownerName = file.ownerName ?? ""
                        submit("open")
                    } label: {
                        HStack {
                            Label(file.name, systemImage: file.ownedByMe?.boolValue == false ? "person.2.fill" : "doc")
                            Spacer()
                            if file.ownedByMe?.boolValue == false {
                                Text(file.ownerName ?? NSLocalizedString("remote_screen.badge.shared", comment: ""))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    private func signInToCloudDrive() {
        busy = true
        let chosenProvider = provider
        Task {
            defer { busy = false }
            do {
                let response = try await perform(RemoteScreenRequest(
                    action: "sign_in", screenId: nil, provider: chosenProvider, location: "",
                    displayName: "", readOnly: false, accountRef: "", ownership: "", ownerName: ""
                ))
                guard response.status == "ok", let ref = response.accountRef, provider == chosenProvider else {
                    if response.status != "cancelled" { result = response }
                    return
                }
                accountRef = ref
                accountMessage = response.message
                if target == nil {
                    let listed = try await perform(RemoteScreenRequest(
                        action: "list_files", screenId: nil, provider: chosenProvider, location: "",
                        displayName: "", readOnly: false, accountRef: ref, ownership: "", ownerName: ""
                    ))
                    if listed.status == "ok" {
                        cloudFiles = listed.files
                        listedCloudFiles = true
                    } else {
                        result = listed
                    }
                }
            } catch {
                result = RemoteScreenResult(status: "error", message: "The remote Screen operation failed")
            }
        }
    }

    private func manageICloudShare() {
        guard let target, let location = target.remoteLocation else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let share = try await CloudKitScreenShareService.shared.shareForManaging(location: location, screenName: target.name)
                CloudSharingPresenter.present(share: share, title: target.name) {
                    submit("disconnect")
                }
            } catch {
                result = RemoteScreenResult(status: "error", message: CloudKitScreenShareService.map(error).message)
            }
        }
    }

    private func perform(_ request: RemoteScreenRequest) async throws -> RemoteScreenResult {
        try await IosDiBridge().remoteScreenActions().perform(request: request)
    }

    private func handlePickedFile(_ picked: Result<URL?, Error>) {
        switch picked {
        case .success(let url):
            guard let url else { return }
            let handle = IosDiBridge().registerLinkedRemoteFile(
                urlString: url.absoluteString,
                displayName: url.lastPathComponent
            )
            location = handle.location
            displayName = handle.displayName
        case .failure(let error):
            result = RemoteScreenResult(status: "error", message: error.localizedDescription)
        }
    }

    private func submit(_ action: String) {
        busy = true
        let request = RemoteScreenRequest(
            action: action, screenId: target?.id, provider: provider, location: location,
            displayName: displayName, readOnly: readOnly, accountRef: accountRef,
            ownership: ownership, ownerName: ownerName
        )
        Task {
            defer { busy = false }
            do {
                let response = try await perform(request)
                result = response
                if action == "open", provider == "icloud_share", response.status == "ok" {
                    CloudKitScreenShareService.shared.pendingShare = nil
                }
                await refresh()
            } catch {
                result = RemoteScreenResult(status: "error", message: "The remote Screen operation failed")
            }
        }
    }
}
