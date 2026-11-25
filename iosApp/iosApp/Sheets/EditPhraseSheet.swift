import SwiftUI
import Shared
import AVFoundation
import PhotosUI
import UniformTypeIdentifiers

struct EditPhraseSheet: View {
    let phrase: Shared.Phrase
    let onClose: () -> Void
    let onSave: (String?, String?, String?) -> Void
    let recorder: AudioRecorder?
    // (phraseId, path)
    let saveRecordingPath: ((String, String?) -> Void)?
    let symbolSearch: SymbolSearch
    @State private var text: String = ""
    @State private var name: String = ""
    @State private var symbolSource: PhraseSymbolSource = .openSymbols
    @State private var symbolQuery: String = ""
    @State private var symbolPackage: SymbolPackageFilter = .all
    @State private var selectedSymbolUrl: String? = nil
    @State private var selectedPhotoItem: PhotosPickerItem? = nil
    @State private var isImportingSymbolFile: Bool = false
    @State private var symbolResults: [OpenSymbolsSymbolResult] = []
    @State private var isSearchingSymbols: Bool = false
    @State private var symbolSearchError: String? = nil
    @State private var hasExplicitSymbolChange: Bool = false
    @State private var recordingUrl: URL? = nil
    @State private var micDenied: Bool = false
    @State private var isRecording: Bool = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Button("category.close", action: onClose)
                    Spacer()
                    Text("phrase.edit.title")
                        .font(.headline)
                        .bold()
                    Spacer()
                    Button("phrase.save") {
                        let updatedImageUrl: String? = hasExplicitSymbolChange ? (selectedSymbolUrl ?? "") : nil
                        onSave(
                            text.trimmingCharacters(in: .whitespacesAndNewlines),
                            name.trimmingCharacters(in: .whitespacesAndNewlines),
                            updatedImageUrl
                        )
                    }
                    .fontWeight(.semibold)
                    .foregroundColor(.accentColor)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 16)
                
                Divider()
                
                // Content
                ScrollView {
                    VStack(spacing: 20) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("phrase.symbol.section")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)

                            Picker("phrase.symbol.source", selection: $symbolSource) {
                                Text("phrase.symbol.source.opensymbols").tag(PhraseSymbolSource.openSymbols)
                                Text("phrase.symbol.source.user_photos").tag(PhraseSymbolSource.userPhotos)
                            }
                            .pickerStyle(.segmented)

                            if symbolSource == .openSymbols {
                                Picker("phrase.symbol.package", selection: $symbolPackage) {
                                    ForEach(SymbolPackageFilter.allCases) { option in
                                        Text(option.localizedTitle).tag(option)
                                    }
                                }
                                .pickerStyle(.segmented)

                                HStack(spacing: 8) {
                                    TextField(NSLocalizedString("phrase.symbol.search.placeholder", comment: ""), text: $symbolQuery)
                                        .textFieldStyle(.roundedBorder)
                                        .textInputAutocapitalization(.never)
                                        .autocorrectionDisabled(true)
                                        .onSubmit {
                                            Task { await searchOpenSymbols() }
                                        }

                                    Button(action: {
                                        Task { await searchOpenSymbols() }
                                    }) {
                                        if isSearchingSymbols {
                                            ProgressView()
                                        } else {
                                            Text("common.search")
                                        }
                                    }
                                    .disabled(isSearchingSymbols || symbolQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                                }
                            } else {
                                HStack(spacing: 8) {
                                    PhotosPicker(selection: $selectedPhotoItem, matching: .images, photoLibrary: .shared()) {
                                        Label("phrase.symbol.pick_photo", systemImage: "photo.on.rectangle")
                                    }
                                    .buttonStyle(.bordered)

                                    Button(action: { isImportingSymbolFile = true }) {
                                        Label("phrase.symbol.import_file", systemImage: "folder")
                                    }
                                    .buttonStyle(.bordered)
                                }
                            }

                            if let selectedUrl = selectedSymbolUrl,
                               let url = URL(string: selectedUrl) {
                                HStack(spacing: 12) {
                                    AsyncImage(url: url) { phase in
                                        switch phase {
                                        case .success(let image):
                                            image
                                                .resizable()
                                                .scaledToFit()
                                        case .failure(_):
                                            Image(systemName: "photo")
                                                .foregroundStyle(.secondary)
                                        case .empty:
                                            ProgressView()
                                        @unknown default:
                                            EmptyView()
                                        }
                                    }
                                    .frame(width: 56, height: 56)
                                    .background(Color(.secondarySystemBackground))
                                    .cornerRadius(10)

                                    Text("phrase.symbol.selected")
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)

                                    Spacer()

                                    Button("common.clear") {
                                        selectedSymbolUrl = nil
                                        hasExplicitSymbolChange = true
                                    }
                                    .font(.footnote)
                                }
                            }

                            if symbolSource == .openSymbols && !symbolResults.isEmpty {
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 10) {
                                        ForEach(symbolResults.prefix(30), id: \.id) { symbol in
                                            Button(action: {
                                                symbolSource = .openSymbols
                                                selectedSymbolUrl = symbol.image_url
                                                hasExplicitSymbolChange = true
                                            }) {
                                                VStack(spacing: 6) {
                                                    if let imageUrl = symbol.image_url,
                                                       let url = URL(string: imageUrl) {
                                                        AsyncImage(url: url) { phase in
                                                            switch phase {
                                                            case .success(let image):
                                                                image
                                                                    .resizable()
                                                                    .scaledToFit()
                                                            case .failure(_):
                                                                Image(systemName: "photo")
                                                                    .foregroundStyle(.secondary)
                                                            case .empty:
                                                                ProgressView()
                                                            @unknown default:
                                                                EmptyView()
                                                            }
                                                        }
                                                        .frame(width: 56, height: 56)
                                                    } else {
                                                        Image(systemName: "photo")
                                                            .frame(width: 56, height: 56)
                                                            .foregroundStyle(.secondary)
                                                    }

                                                    Text(symbol.name)
                                                        .font(.caption2)
                                                        .lineLimit(2)
                                                        .multilineTextAlignment(.center)
                                                    Text(SymbolPackageFilter.title(for: symbol.source))
                                                        .font(.caption2)
                                                        .foregroundStyle(.secondary)
                                                }
                                                .padding(8)
                                                .frame(width: 92, height: 126)
                                                .background(
                                                    RoundedRectangle(cornerRadius: 10)
                                                        .fill(selectedSymbolUrl == symbol.image_url ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
                                                )
                                            }
                                            .buttonStyle(.plain)
                                        }
                                    }
                                }
                            }

                            if let symbolSearchError {
                                Text(symbolSearchError)
                                    .font(.footnote)
                                    .foregroundColor(.red)
                            }
                        }

                        // Name input (optional)
                        VStack(alignment: .leading, spacing: 8) {
                            Text("phrase.title")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            TextField(NSLocalizedString("phrase.title.placeholder", comment: ""), text: $name)
                                .textFieldStyle(.plain)
                                .font(.system(size: 16))
                                .padding(16)
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color(.separator), lineWidth: 1)
                                )
                        }
                        
                        // Text input
                        VStack(alignment: .leading, spacing: 8) {
                            Text("phrase.pronunciation")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            TextEditor(text: $text)
                                .frame(minHeight: 120)
                                .padding(12)
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color(.separator), lineWidth: 1)
                                )
                        }
                        
                        // Recording section
                        VStack(alignment: .leading, spacing: 12) {
                            Text("phrase.recording")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            VStack(spacing: 12) {
                                HStack(spacing: 12) {
                                    if isRecording {
                                        Button(role: .destructive) {
                                            Task {
                                                if let url = try? await recorder?.stopRecording() {
                                                    await MainActor.run {
                                                        recordingUrl = url
                                                        isRecording = false
                                                    }
                                                    if let cb = saveRecordingPath {
                                                        cb(phrase.id, url.path)
                                                    }
                                                }
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                Image(systemName: "stop.circle.fill")
                                                Text("common.stop")
                                            }
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 12)
                                            .background(Color.red)
                                            .cornerRadius(25)
                                        }
                                    } else {
                                        Button {
                                            requestMicPermission { granted in
                                                guard granted else { micDenied = true; return }
                                                Task {
                                                    _ = try? await recorder?.startRecording()
                                                    await MainActor.run { isRecording = true }
                                                }
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                Image(systemName: "mic.circle.fill")
                                                Text("phrase.record")
                                            }
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 12)
                                            .background(Color.accentColor)
                                            .cornerRadius(25)
                                        }
                                    }
                                    
                                    Spacer()
                                }
                                
                                if let url = recordingUrl {
                                    HStack(spacing: 16) {
                                        Button { recorder?.play(url: url) } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "play.circle.fill")
                                                Text("phrase.play_tts")
                                            }
                                            .font(.system(size: 14, weight: .medium))
                                            .foregroundColor(.accentColor)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(Color.accentColor.opacity(0.1))
                                            .cornerRadius(20)
                                        }
                                        
                                        Button { recorder?.stopPlayback() } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "stop.circle")
                                                Text("common.stop")
                                            }
                                            .font(.system(size: 14, weight: .medium))
                                            .foregroundColor(.secondary)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(Color(.tertiarySystemBackground))
                                            .cornerRadius(20)
                                        }

                                        Button(role: .destructive) {
                                            recorder?.stopPlayback()
                                            if FileManager.default.fileExists(atPath: url.path) {
                                                try? FileManager.default.removeItem(at: url)
                                            }
                                            recordingUrl = nil
                                            saveRecordingPath?(phrase.id, nil)
                                        } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "trash")
                                                Text("phrase.delete")
                                            }
                                            .font(.system(size: 14, weight: .medium))
                                            .foregroundColor(.red)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(Color.red.opacity(0.12))
                                            .cornerRadius(20)
                                        }
                                        
                                        Spacer()
                                    }
                                }
                                
                                if micDenied {
                                    HStack {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundColor(.orange)
                                        Text("phrase.mic_permission_denied")
                                            .font(.footnote)
                                            .foregroundColor(.secondary)
                                    }
                                    .padding(12)
                                    .background(Color.orange.opacity(0.1))
                                    .cornerRadius(8)
                                }
                            }
                            .padding(16)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 20)
                }
            }
            .onAppear {
                text = phrase.text
                name = phrase.name ?? ""
                selectedSymbolUrl = phrase.imageUrl
                symbolSource = (phrase.imageUrl?.hasPrefix("http") == true) ? .openSymbols : .userPhotos
                if let path = phrase.recordingPath,
                   !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                   FileManager.default.fileExists(atPath: path) {
                    recordingUrl = URL(fileURLWithPath: path)
                }
            }
        }
        .interactiveDismissDisabled(false)
        .onChange(of: selectedPhotoItem) { _, newItem in
            guard let newItem else { return }
            Task {
                await importPhotoItem(newItem)
            }
        }
        .fileImporter(
            isPresented: $isImportingSymbolFile,
            allowedContentTypes: [.image],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                Task {
                    await importImageFile(url)
                }
            case .failure:
                symbolSearchError = NSLocalizedString("phrase.symbol.error.import_failed", comment: "")
            }
        }
    }

    private func requestMicPermission(_ cb: @escaping (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in cb(granted) }
    }

    private func searchOpenSymbols() async {
        let trimmed = symbolQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        await MainActor.run {
            isSearchingSymbols = true
            symbolSearchError = nil
            symbolResults = []
        }

        let (results, errorCode) = await searchSymbols(trimmed, packageFilter: symbolPackage, using: symbolSearch)

        await MainActor.run {
            if let errorCode {
                symbolSearchError = NSLocalizedString("phrase.symbol.error.\(errorCode)", comment: "")
            } else {
                symbolResults = results
            }
            isSearchingSymbols = false
        }
    }

    private func importPhotoItem(_ item: PhotosPickerItem) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                await MainActor.run {
                    symbolSearchError = NSLocalizedString("phrase.symbol.error.photo_load_failed", comment: "")
                }
                return
            }
            let imageUrl = try persistImageData(data, preferredExtension: "jpg")
            await MainActor.run {
                symbolSource = .userPhotos
                selectedSymbolUrl = imageUrl
                hasExplicitSymbolChange = true
                symbolSearchError = nil
            }
        } catch {
            await MainActor.run {
                symbolSearchError = NSLocalizedString("phrase.symbol.error.photo_load_failed", comment: "")
            }
        }
    }

    private func importImageFile(_ sourceUrl: URL) async {
        do {
            let shouldStop = sourceUrl.startAccessingSecurityScopedResource()
            defer {
                if shouldStop { sourceUrl.stopAccessingSecurityScopedResource() }
            }

            let data = try Data(contentsOf: sourceUrl)
            let ext = sourceUrl.pathExtension.isEmpty ? "png" : sourceUrl.pathExtension
            let imageUrl = try persistImageData(data, preferredExtension: ext)
            await MainActor.run {
                symbolSource = .userPhotos
                selectedSymbolUrl = imageUrl
                hasExplicitSymbolChange = true
                symbolSearchError = nil
            }
        } catch {
            await MainActor.run {
                symbolSearchError = NSLocalizedString("phrase.symbol.error.import_failed", comment: "")
            }
        }
    }

    private func persistImageData(_ data: Data, preferredExtension: String) throws -> String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let symbolsDir = docs.appendingPathComponent("WingmateSymbols", isDirectory: true)
        if !FileManager.default.fileExists(atPath: symbolsDir.path) {
            try FileManager.default.createDirectory(at: symbolsDir, withIntermediateDirectories: true)
        }
        let filename = "\(UUID().uuidString).\(preferredExtension)"
        let destination = symbolsDir.appendingPathComponent(filename)
        try data.write(to: destination, options: .atomic)
        return destination.absoluteString
    }
}

