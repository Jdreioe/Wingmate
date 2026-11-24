import CloudKit
import Foundation
import Shared
import UIKit

/// Address of one shared Screen record.
///
/// - Owner: `icloud://private/<zone>/<record>` (the record lives in the owner's private database).
/// - Participant: `icloud://shared/<zoneOwner>/<zone>/<record>` (the record is read through the shared database).
struct CloudKitScreenAddress {
    let isOwner: Bool
    let recordID: CKRecord.ID

    var location: String {
        let zone = recordID.zoneID
        if isOwner {
            return "icloud://private/\(Self.encode(zone.zoneName))/\(Self.encode(recordID.recordName))"
        }
        return "icloud://shared/\(Self.encode(zone.ownerName))/\(Self.encode(zone.zoneName))/\(Self.encode(recordID.recordName))"
    }

    init(isOwner: Bool, recordID: CKRecord.ID) {
        self.isOwner = isOwner
        self.recordID = recordID
    }

    init?(location: String) {
        let prefix = "icloud://"
        guard location.hasPrefix(prefix) else { return nil }
        let parts = location.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false)
            .map { String($0).removingPercentEncoding ?? String($0) }
        guard parts.allSatisfy({ !$0.isEmpty }) else { return nil }
        switch (parts.first, parts.count) {
        case ("private", 3):
            let zone = CKRecordZone.ID(zoneName: parts[1], ownerName: CKCurrentUserDefaultName)
            self.init(isOwner: true, recordID: CKRecord.ID(recordName: parts[2], zoneID: zone))
        case ("shared", 4):
            let zone = CKRecordZone.ID(zoneName: parts[2], ownerName: parts[1])
            self.init(isOwner: false, recordID: CKRecord.ID(recordName: parts[3], zoneID: zone))
        default:
            return nil
        }
    }

    private static func encode(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

/// A share the user accepted from an iCloud link, waiting to be opened as a Screen.
struct AcceptedCloudKitScreenShare: Equatable, Identifiable {
    let location: String
    let title: String
    let readOnly: Bool
    var id: String { location }
}

/// An error that maps onto `RemoteStorageErrorCode` in the shared module.
struct CloudKitScreenError: LocalizedError {
    let code: String
    let message: String
    var errorDescription: String? { message }
}

/// iOS → iOS Screen sharing through CloudKit (CKShare).
///
/// The Screen owner keeps one `WingmateScreen` record per shared Screen in a custom zone of their
/// private database and invites participants with `UICloudSharingController`. Participants read and
/// write the same record through their shared database. Wingmate runs no server for this and the
/// data counts against the owner's own iCloud storage.
final class CloudKitScreenShareService: NSObject, ObservableObject, IosCloudKitScreenBridge {
    static let shared = CloudKitScreenShareService()

    static let zoneName = "WingmateScreens"
    static let recordType = "WingmateScreen"

    private enum Field {
        static let name = "name"
        static let package = "package"
        static let packageSize = "packageSize"
    }

    /// Accepted share that the Screens workspace should offer to open.
    @MainActor @Published var pendingShare: AcceptedCloudKitScreenShare?

    let container = CKContainer.default()

    private func database(for address: CloudKitScreenAddress) -> CKDatabase {
        address.isOwner ? container.privateCloudDatabase : container.sharedCloudDatabase
    }

    // MARK: - IosCloudKitScreenBridge (called from Kotlin)

    func fetchScreenRecord(location: String, includePackage: Bool, completion: @escaping (IosCloudKitRecordResult) -> Void) {
        Task {
            do {
                let address = try Self.address(location)
                let keys = includePackage ? [Field.packageSize, Field.package] : [Field.packageSize]
                guard let record = try await fetchRecord(address, desiredKeys: keys) else {
                    completion(IosCloudKitRecordResult(found: false, revision: nil, packageData: nil, errorCode: nil, errorMessage: nil))
                    return
                }
                let hasPackage = record[Field.packageSize] != nil
                var data: Data?
                if includePackage, hasPackage, let fileURL = (record[Field.package] as? CKAsset)?.fileURL {
                    data = try Data(contentsOf: fileURL)
                }
                completion(IosCloudKitRecordResult(
                    found: true,
                    revision: hasPackage ? record.recordChangeTag : nil,
                    packageData: data,
                    errorCode: nil,
                    errorMessage: nil
                ))
            } catch {
                let mapped = Self.map(error)
                completion(IosCloudKitRecordResult(found: false, revision: nil, packageData: nil, errorCode: mapped.code, errorMessage: mapped.message))
            }
        }
    }

    func saveScreenRecord(location: String, packageData: Data, expectedRevision: String?, completion: @escaping (IosCloudKitWriteResult) -> Void) {
        Task {
            let temporaryURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("wingmate-share-\(UUID().uuidString).obz")
            defer { try? FileManager.default.removeItem(at: temporaryURL) }
            do {
                let address = try Self.address(location)
                guard let record = try await fetchRecord(address, desiredKeys: [Field.packageSize]) else {
                    throw CloudKitScreenError(code: "NOT_FOUND", message: NSLocalizedString("remote_screen.icloud.error.missing", comment: ""))
                }
                let currentRevision = record[Field.packageSize] != nil ? record.recordChangeTag : nil
                guard currentRevision == expectedRevision else {
                    throw CloudKitScreenError(code: "CONFLICT", message: "The shared Screen changed in iCloud before saving")
                }
                try packageData.write(to: temporaryURL, options: .atomic)
                record[Field.package] = CKAsset(fileURL: temporaryURL)
                record[Field.packageSize] = packageData.count as NSNumber
                let result = try await database(for: address).modifyRecords(
                    saving: [record],
                    deleting: [],
                    savePolicy: .ifServerRecordUnchanged,
                    atomically: true
                )
                guard let saved = try result.saveResults[record.recordID]?.get() else {
                    throw CloudKitScreenError(code: "IO_ERROR", message: "iCloud did not confirm the save")
                }
                completion(IosCloudKitWriteResult(revision: saved.recordChangeTag, errorCode: nil, errorMessage: nil))
            } catch {
                let mapped = Self.map(error)
                completion(IosCloudKitWriteResult(revision: nil, errorCode: mapped.code, errorMessage: mapped.message))
            }
        }
    }

    func releaseScreenRecord(location: String, completion: @escaping (IosCloudKitWriteResult) -> Void) {
        Task {
            do {
                try await release(location: location)
                completion(IosCloudKitWriteResult(revision: nil, errorCode: nil, errorMessage: nil))
            } catch {
                let mapped = Self.map(error)
                completion(IosCloudKitWriteResult(revision: nil, errorCode: mapped.code, errorMessage: mapped.message))
            }
        }
    }

    // MARK: - Owner: create and manage shares

    /// Creates an empty Screen record plus its CKShare in the owner's private database.
    /// Wingmate's shared sync then uploads the package to this record.
    func createShare(screenName: String) async throws -> (location: String, share: CKShare) {
        try await requireAccount()
        let zone = CKRecordZone(zoneName: Self.zoneName)
        _ = try await container.privateCloudDatabase.modifyRecordZones(saving: [zone], deleting: [])
        let record = CKRecord(
            recordType: Self.recordType,
            recordID: CKRecord.ID(recordName: UUID().uuidString, zoneID: zone.zoneID)
        )
        record[Field.name] = screenName as NSString
        let share = CKShare(rootRecord: record)
        share[CKShare.SystemFieldKey.title] = screenName as NSString
        share.publicPermission = .none
        let result = try await container.privateCloudDatabase.modifyRecords(
            saving: [record, share],
            deleting: [],
            savePolicy: .ifServerRecordUnchanged,
            atomically: true
        )
        guard let savedShare = try result.saveResults[share.recordID]?.get() as? CKShare else {
            throw CloudKitScreenError(code: "IO_ERROR", message: "iCloud did not confirm the share")
        }
        return (CloudKitScreenAddress(isOwner: true, recordID: record.recordID).location, savedShare)
    }

    /// Returns the share for a linked Screen so people can be invited, changed, or removed.
    /// Owners get a fresh share if they previously stopped sharing.
    func shareForManaging(location: String, screenName: String) async throws -> CKShare {
        try await requireAccount()
        let address = try Self.address(location)
        let db = database(for: address)
        guard let record = try await fetchRecord(address, desiredKeys: [Field.name]) else {
            throw CloudKitScreenError(code: "NOT_FOUND", message: NSLocalizedString("remote_screen.icloud.error.missing", comment: ""))
        }
        if let shareID = record.share?.recordID,
           let share = try await db.records(for: [shareID], desiredKeys: nil)[shareID].flatMap({ try? $0.get() }) as? CKShare {
            return share
        }
        guard address.isOwner else {
            throw CloudKitScreenError(code: "NOT_FOUND", message: NSLocalizedString("remote_screen.icloud.error.missing", comment: ""))
        }
        let share = CKShare(rootRecord: record)
        share[CKShare.SystemFieldKey.title] = screenName as NSString
        share.publicPermission = .none
        let result = try await db.modifyRecords(saving: [record, share], deleting: [], savePolicy: .ifServerRecordUnchanged, atomically: true)
        guard let savedShare = try result.saveResults[share.recordID]?.get() as? CKShare else {
            throw CloudKitScreenError(code: "IO_ERROR", message: "iCloud did not confirm the share")
        }
        return savedShare
    }

    /// Owner: stops sharing and deletes the record. Participant: leaves the share.
    func release(location: String) async throws {
        let address = try Self.address(location)
        let db = database(for: address)
        guard let record = try await fetchRecord(address, desiredKeys: [Field.name]) else { return }
        var deleting: [CKRecord.ID] = []
        if let shareID = record.share?.recordID { deleting.append(shareID) }
        if address.isOwner { deleting.append(record.recordID) }
        guard !deleting.isEmpty else { return }
        _ = try await db.modifyRecords(saving: [], deleting: deleting)
    }

    // MARK: - Participant: accept shares

    /// Accepts a share link the user tapped and queues it for the Screens workspace to open.
    func accept(_ metadata: CKShare.Metadata) async {
        guard let rootID = metadata.hierarchicalRootRecordID else { return }
        let isOwner = metadata.participantRole == .owner
        do {
            if !isOwner {
                _ = try await CKContainer(identifier: metadata.containerIdentifier).accept(metadata)
            }
        } catch {
            // Already-accepted shares fail harmlessly here; the record fetch reports real problems.
        }
        let address = isOwner
            ? CloudKitScreenAddress(
                isOwner: true,
                recordID: CKRecord.ID(
                    recordName: rootID.recordName,
                    zoneID: CKRecordZone.ID(zoneName: rootID.zoneID.zoneName, ownerName: CKCurrentUserDefaultName)
                )
            )
            : CloudKitScreenAddress(isOwner: false, recordID: rootID)
        let title = (metadata.share[CKShare.SystemFieldKey.title] as? String) ?? ""
        let accepted = AcceptedCloudKitScreenShare(
            location: address.location,
            title: title,
            readOnly: !isOwner && metadata.participantPermission == .readOnly
        )
        await MainActor.run { pendingShare = accepted }
    }

    // MARK: - Helpers

    private func fetchRecord(_ address: CloudKitScreenAddress, desiredKeys: [CKRecord.FieldKey]?) async throws -> CKRecord? {
        do {
            let results = try await database(for: address).records(for: [address.recordID], desiredKeys: desiredKeys)
            guard let result = results[address.recordID] else { return nil }
            return try result.get()
        } catch let error as CKError where Self.isMissing(error) {
            return nil
        }
    }

    private func requireAccount() async throws {
        let status = try await container.accountStatus()
        guard status == .available else {
            throw CloudKitScreenError(code: "UNAUTHORIZED", message: NSLocalizedString("remote_screen.icloud.error.account", comment: ""))
        }
    }

    private static func address(_ location: String) throws -> CloudKitScreenAddress {
        guard let address = CloudKitScreenAddress(location: location) else {
            throw CloudKitScreenError(code: "INVALID_CONFIGURATION", message: "The iCloud share address is not valid")
        }
        return address
    }

    private static func isMissing(_ error: CKError) -> Bool {
        switch error.code {
        case .unknownItem, .zoneNotFound, .userDeletedZone: return true
        case .partialFailure:
            return error.partialErrorsByItemID?.values.allSatisfy { ($0 as? CKError).map(isMissing) ?? false } ?? false
        default: return false
        }
    }

    static func map(_ error: Error) -> CloudKitScreenError {
        if let error = error as? CloudKitScreenError { return error }
        guard let ckError = error as? CKError else {
            return CloudKitScreenError(code: "IO_ERROR", message: error.localizedDescription)
        }
        switch ckError.code {
        case .networkUnavailable, .networkFailure, .serviceUnavailable, .requestRateLimited, .zoneBusy:
            return CloudKitScreenError(code: "NETWORK_UNAVAILABLE", message: "iCloud is unavailable. Changes stay on this device")
        case .notAuthenticated, .badContainer, .missingEntitlement:
            return CloudKitScreenError(code: "UNAUTHORIZED", message: NSLocalizedString("remote_screen.icloud.error.account", comment: ""))
        case .permissionFailure:
            return CloudKitScreenError(code: "READ_ONLY", message: NSLocalizedString("remote_screen.icloud.error.read_only", comment: ""))
        case .serverRecordChanged:
            return CloudKitScreenError(code: "CONFLICT", message: "The shared Screen changed in iCloud before saving")
        case .unknownItem, .zoneNotFound, .userDeletedZone:
            return CloudKitScreenError(code: "NOT_FOUND", message: NSLocalizedString("remote_screen.icloud.error.missing", comment: ""))
        case .quotaExceeded:
            return CloudKitScreenError(code: "IO_ERROR", message: NSLocalizedString("remote_screen.icloud.error.quota", comment: ""))
        case .partialFailure:
            if let first = ckError.partialErrorsByItemID?.values.first { return map(first) }
            return CloudKitScreenError(code: "IO_ERROR", message: ckError.localizedDescription)
        default:
            return CloudKitScreenError(code: "IO_ERROR", message: ckError.localizedDescription)
        }
    }
}

/// Presents Apple's sharing sheet for inviting people to a Screen and changing their access.
@MainActor
enum CloudSharingPresenter {
    private static var activeDelegate: Delegate?

    static func present(share: CKShare, title: String) {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        guard var top = (windows.first(where: \.isKeyWindow) ?? windows.first)?.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }

        let controller = UICloudSharingController(share: share, container: CloudKitScreenShareService.shared.container)
        controller.availablePermissions = [.allowPrivate, .allowReadWrite, .allowReadOnly]
        let delegate = Delegate(title: title)
        activeDelegate = delegate
        controller.delegate = delegate
        if let popover = controller.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        top.present(controller, animated: true)
    }

    private final class Delegate: NSObject, UICloudSharingControllerDelegate {
        let title: String
        init(title: String) { self.title = title }

        func itemTitle(for csc: UICloudSharingController) -> String? { title }

        func cloudSharingController(_ csc: UICloudSharingController, failedToSaveShareWithError error: Error) {}
    }
}
