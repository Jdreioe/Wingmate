import SwiftUI
import Shared
import UniformTypeIdentifiers

struct GeneralSettingsView: View {
    @ObservedObject var model: IosViewModel
    let onRestartSetup: () -> Void

    var body: some View {
        Form {
            Section {
                Picker("settings.general.startup_mode", selection: Binding(
                    get: { model.startupUsesScreens }, set: { model.setStartupUsesScreens($0) }
                )) {
                    Text("settings.general.keyboard").tag(false)
                    Text("settings.general.screens").tag(true)
                }
                .pickerStyle(.segmented)

                if model.startupUsesScreens {
                    Picker("settings.general.startup_screen", selection: Binding(
                        get: { model.startupBoardSetId ?? "" },
                        set: { model.setStartupBoardSetId($0.isEmpty ? nil : $0) }
                    )) {
                        Text("settings.general.screen_library").tag("")
                        ForEach(model.boardSets) { set in
                            Text(set.name).tag(set.id)
                        }
                    }
                }
            } header: {
                Text("settings.general.startup")
            } footer: {
                Text("settings.general.startup.footer")
            }

            Section {
                EditingAccessSettingsView(model: model)
            } header: {
                Text("editing_access.title")
            } footer: {
                Text("editing_access.description")
            }

            Section {
                Button(action: onRestartSetup) {
                    Label("settings.restart_setup", systemImage: "arrow.counterclockwise")
                }
            }
        }
        .navigationTitle(Text("settings.category.general"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct EditingAccessSettingsView: View {
    @ObservedObject var model: IosViewModel
    @State private var pinMode: EditingAccessPinMode?

    var body: some View {
        Group {
            if !model.editingAccessSupported {
                Text("editing_access.unavailable").foregroundStyle(.red)
            } else {
                if model.editingAccessEnabled {
                    Button("editing_access.change") { pinMode = .change }
                        .frame(minHeight: 44)
                } else {
                    Button("editing_access.enable") { pinMode = .create }
                        .frame(minHeight: 44)
                }
                if model.editingAccessEnabled {
                    Button("editing_access.disable", role: .destructive) {
                        pinMode = .disable
                    }
                    .frame(minHeight: 44)
                }
            }
        }
        .task { await model.refreshEditingAccess() }
        .sheet(item: $pinMode) { mode in
            EditingAccessPinSheet(model: model, mode: mode, onSuccess: {
                pinMode = nil
            }, onCancel: { pinMode = nil })
        }
    }
}

struct PrivacySettingsView: View {
    @ObservedObject var model: IosViewModel
    @State private var importingBackup = false
    @State private var backupStatus: String? = nil
    @State private var confirmRestoreURL: URL? = nil

    var body: some View {
        Form {
            Section {
                Button("backup.create") {
                    Task {
                        do {
                            let result = try await model.shareCompleteBackup()
                            backupStatus = result.isSuccess
                                ? NSLocalizedString("backup.exported", comment: "")
                                : result.message ?? NSLocalizedString("backup.failed", comment: "")
                        } catch is CancellationError {
                            return
                        } catch {
                            backupStatus = NSLocalizedString("backup.failed", comment: "")
                        }
                    }
                }
                Button("backup.restore") { importingBackup = true }
                if let backupStatus { Text(backupStatus).font(.footnote) }
            } header: {
                Text("backup.title")
            } footer: {
                Text("backup.description")
            }

            Section {
                Toggle("settings.privacy.show_history", isOn: Binding(
                    get: { model.historyVisible },
                    set: { model.setHistoryVisible($0) }
                ))
                Toggle("settings.likely_next", isOn: Binding(get: { model.likelyNextPageEnabled }, set: { model.setLikelyNextPageEnabled($0) }))
                    .disabled(!model.historyVisible)
                Text("settings.likely_next.footer").font(.footnote).foregroundStyle(.secondary)
            } header: {
                Text("settings.privacy.local_data")
            } footer: {
                Text("settings.privacy.show_history.footer")
            }

            Section {
                ForEach(model.boardSets) { set in
                    Toggle(set.name, isOn: Binding(
                        get: { set.cacheWholeSentences },
                        set: { model.setBoardSetSentenceCaching(id: set.id, enabled: $0) }
                    ))
                }
            } header: {
                Text("settings.privacy.board_cache")
            } footer: {
                Text("settings.privacy.board_cache.footer")
            }

            Section {
                Toggle("settings.general.analytics", isOn: Binding(
                    get: { model.featureUsageReportingEnabled },
                    set: { model.setFeatureUsageReportingEnabled($0) }
                ))
                Toggle("settings.accessibility.usage_logging", isOn: Binding(
                    get: { model.usageLoggingEnabled },
                    set: { model.setUsageLoggingEnabled($0) }
                ))
            } header: {
                Text("settings.privacy.data_collection")
            } footer: {
                Text("settings.general.analytics.footer")
            }
        }
        .navigationTitle(Text("settings.category.privacy"))
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $importingBackup, allowedContentTypes: [.zip, .data]) { result in
            if case .success(let url) = result { confirmRestoreURL = url }
        }
        .confirmationDialog(
            "backup.replace_title",
            isPresented: Binding(get: { confirmRestoreURL != nil }, set: { if !$0 { confirmRestoreURL = nil } }),
            titleVisibility: .visible
        ) {
            Button("backup.replace_action", role: .destructive) {
                guard let url = confirmRestoreURL else { return }
                confirmRestoreURL = nil
                Task {
                    let accessed = url.startAccessingSecurityScopedResource()
                    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                    do {
                        let result = try await model.restoreCompleteBackup(path: url.path)
                        if result.isSuccess {
                            backupStatus = NSLocalizedString("backup.restored", comment: "")
                            await model.refreshParitySettings()
                            await model.refreshAzureConfiguration()
                            model.refreshVoiceAndLanguages()
                            await model.loadBoardSets()
                        } else {
                            backupStatus = result.message ?? NSLocalizedString("backup.failed", comment: "")
                        }
                    } catch is CancellationError {
                        return
                    } catch {
                        backupStatus = NSLocalizedString("backup.failed", comment: "")
                    }
                }
            }
            Button("common.cancel", role: .cancel) { confirmRestoreURL = nil }
        } message: {
            Text("backup.replace_warning")
        }
    }
}
