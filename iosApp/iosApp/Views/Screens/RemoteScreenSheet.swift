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
    @State private var provider = "webdav"
    @State private var location = ""
    @State private var displayName = ""
    @State private var username = ""
    @State private var secret = ""
    @State private var branch = ""
    @State private var filePath = ""
    @State private var busy = false
    @State private var showFileImporter = false
    @State private var showFileExporter = false
    @State private var result: RemoteScreenResult?
    @State private var readOnly = false
    @State private var cloudProviders: [String] = []
    @State private var accountRef = ""
    @State private var accountMessage: String?
    @State private var cloudFiles: [RemoteFileChoice] = []
    @State private var listedCloudFiles = false

    private var isICloudLink: Bool { target?.remoteKind == "icloud_share" }
    private var isCloudDrive: Bool { provider == "google_drive" || provider == "onedrive" }
    private var cloudProviderName: String {
        NSLocalizedString(provider == "google_drive" ? "remote_screen.provider.google_drive" : "remote_screen.provider.onedrive", comment: "")
    }

    private var conflict: Bool {
        result?.status == "conflict" || result?.status == "read_only_conflict" || target?.remoteConflict == true
    }

    var body: some View {
        NavigationStack {
            Form {
                if let remoteName = target?.remoteName {
                    Section("remote_screen.storage") {
                        Text(remoteName)
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
                        Button("remote_screen.disconnect") { submit("disconnect") }
                        if isICloudLink {
                            Text("remote_screen.icloud.disconnect_note")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                } else if result?.status != "ok" {
                    Section {
                        Picker("remote_screen.provider.label", selection: $provider) {
                            Text("remote_screen.provider.webdav").tag("webdav")
                            Text("remote_screen.provider.github").tag("github")
                            Text("remote_screen.provider.https").tag("https_url")
                            Text("remote_screen.provider.linked_file").tag("linked_file")
                            Text("remote_screen.provider.icloud_share").tag("icloud_share")
                            if cloudProviders.contains("google_drive") {
                                Text("remote_screen.provider.google_drive").tag("google_drive")
                            }
                            if cloudProviders.contains("onedrive") {
                                Text("remote_screen.provider.onedrive").tag("onedrive")
                            }
                        }
                        .onChange(of: provider) {
                            accountRef = ""
                            accountMessage = nil
                            cloudFiles = []
                            listedCloudFiles = false
                            guard acceptedShare == nil || provider != "icloud_share" else { return }
                            location = ""
                            displayName = ""
                            secret = ""
                            readOnly = false
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
                        } else {
                            TextField(
                                LocalizedStringKey(provider == "github" ? "remote_screen.location.repo" : "remote_screen.location.url"),
                                text: $location
                            )
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            if provider == "webdav" {
                                TextField("remote_screen.username", text: $username).textInputAutocapitalization(.never)
                            }
                            if provider == "github" {
                                TextField("remote_screen.branch", text: $branch).textInputAutocapitalization(.never)
                                TextField("remote_screen.file_path", text: $filePath).textInputAutocapitalization(.never)
                            }
                            SecureField("remote_screen.secret", text: $secret)
                        }
                        if provider != "icloud_share" && !isCloudDrive {
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
                    displayName: "", username: "", secret: "", branch: "", filePath: "", readOnly: false,
                    accountRef: ""
                ))
                result = response
                await refresh()
                if response.status == "ok" {
                    CloudSharingPresenter.present(share: prepared.share, title: target.name)
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
                        submit("open")
                    } label: {
                        Label(file.name, systemImage: "doc")
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
                    displayName: "", username: "", secret: "", branch: "", filePath: "", readOnly: false,
                    accountRef: ""
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
                        displayName: "", username: "", secret: "", branch: "", filePath: "", readOnly: false,
                        accountRef: ref
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
                CloudSharingPresenter.present(share: share, title: target.name)
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
            displayName: displayName, username: username, secret: secret, branch: branch, filePath: filePath,
            readOnly: readOnly, accountRef: accountRef
        )
        Task {
            defer { busy = false }
            do {
                let response = try await perform(request)
                result = response
                if response.status != "error" && response.status != "locked" { secret = "" }
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
