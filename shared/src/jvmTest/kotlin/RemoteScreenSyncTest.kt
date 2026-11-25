import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.DefaultEditingAccessStore
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.InMemorySecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.RemoteScreenActions
import io.github.jdreioe.wingmate.application.RemoteScreenRequest
import io.github.jdreioe.wingmate.application.RemoteScreenSyncUseCase
import io.github.jdreioe.wingmate.domain.obf.CloudDriveFiles
import io.github.jdreioe.wingmate.domain.obf.CloudDriveSignIn
import io.github.jdreioe.wingmate.domain.obf.CloudShareRecord
import io.github.jdreioe.wingmate.domain.obf.RemoteFileChoice
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.github.jdreioe.wingmate.domain.obf.CloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.LinkedFileHandle
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteFileContent
import io.github.jdreioe.wingmate.domain.obf.RemoteFileMetadata
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenLink
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenOwnership
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteWriteResult
import io.github.jdreioe.wingmate.domain.obf.UnsupportedRemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.domain.obf.SyncFolderHandle
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.CloudShareRemoteScreenProvider
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.LinkedFileRemoteScreenProvider
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ArchiveEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteScreenSyncTest {
    @Test
    fun automaticZeroInputSyncPullsOntoLockedScreenAndPreservesLocalEditsOnSaveAsCopy() = runBlocking {
        val storage = RemoteStorage()
        val supporter = Device(storage)
        val communicator = Device(storage)
        val original = supporter.boards.createBoardSet("Home", 2, 2)
        assertEquals("ok", supporter.perform("connect", original.id))
        assertEquals("ok", communicator.perform("open"))
        val installed = communicator.boards.listBoardSets().single()
        assertNotEquals(original.id, installed.id)
        assertEquals(original.rootBoardId, installed.rootBoardId)
        assertEquals("Home", installed.name)

        // Lock both Screen lock AND Editing access on the Communicator's device.
        communicator.boards.toggleLocked(installed.id)
        communicator.boards.setSentenceCaching(installed.id, false)
        communicator.editingAccess.configure("1234")
        communicator.editingAccess.lock()

        // Supporter edits and syncs; Communicator's automatic sync pulls with zero user input
        // while keeping the Screen locked locally and preserving local settings.
        supporter.boards.renameBoardSet(original.id, "Supporter's edit")
        assertEquals("ok", supporter.perform("sync", original.id))
        assertTrue(communicator.actions.syncAllAutomatic(force = true))
        val updated = communicator.boards.listBoardSets().single()
        assertEquals(installed.id, updated.id)
        assertEquals("Supporter's edit", updated.name)
        assertTrue(updated.isLocked)
        assertFalse(updated.cacheWholeSentences)

        // Unlock Communicator to edit locally and create a two-way conflict.
        assertTrue(communicator.editingAccess.unlock("1234"))
        communicator.boards.toggleLocked(installed.id)
        communicator.boards.renameBoardSet(installed.id, "Communicator's edit")
        supporter.boards.renameBoardSet(original.id, "Another supporter edit")
        assertEquals("ok", supporter.perform("sync", original.id))
        val published = storage.file
        assertEquals("conflict", communicator.perform("sync", installed.id))
        assertEquals(published, storage.file)
        assertEquals("Communicator's edit", communicator.boards.getBoardSet(installed.id)?.name)

        // Save remote as a separate copy: remote stays intact AND local remains dirty so a
        // subsequent remote edit NEVER silently overwrites the Communicator's local edit!
        assertEquals("ok", communicator.perform("save_remote_as_copy", installed.id))
        assertEquals(published, storage.file)
        assertTrue(communicator.boards.listBoardSets().any { it.name == "Another supporter edit (remote copy)" })

        supporter.boards.renameBoardSet(original.id, "Third supporter edit")
        assertEquals("ok", supporter.perform("sync", original.id))
        assertEquals("conflict", communicator.perform("sync", installed.id))
        assertEquals("Communicator's edit", communicator.boards.getBoardSet(installed.id)?.name)

        assertEquals("ok", communicator.perform("disconnect", installed.id))
        assertNull(communicator.boards.getBoardSet(installed.id)?.remoteLink)
    }

    @Test
    fun localEditAutomaticallyPushesToRemoteWithNoUserInput() = runBlocking {
        val storage = RemoteStorage()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val supporter = Device(storage, backgroundScope = scope)
            val communicator = Device(storage)
            val screen = supporter.boards.createBoardSet("Home", 2, 2)
            assertEquals("ok", supporter.perform("connect", screen.id))
            assertEquals("ok", communicator.perform("open"))
            val initialHash = storage.file?.contentHash

            // Rename triggers debounced automatic push with zero manual sync call.
            supporter.boards.renameBoardSet(screen.id, "Auto pushed")
            val deadline = System.currentTimeMillis() + 5_000L
            while (storage.file?.contentHash == initialHash && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            assertNotEquals(initialHash, storage.file?.contentHash)

            assertTrue(communicator.actions.syncAllAutomatic(force = true))
            assertEquals("Auto pushed", communicator.boards.listBoardSets().single().name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun linkedFileEndToEndSyncWorksAcrossDevices() = runBlocking {
        val sharedFiles = mutableMapOf<String, ByteArray>("/cloud/home.obz" to ByteArray(0))
        val linkedFileStorage = object : LinkedRemoteFileStorage {
            override val isSupported: Boolean = true
            override suspend fun pickExistingFile(): LinkedFileHandle? = null
            override suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle? = null
            override suspend fun readBytes(location: String): ByteArray =
                sharedFiles[location] ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Missing")
            override suspend fun writeBytes(location: String, bytes: ByteArray) {
                sharedFiles[location] = bytes
            }
        }
        val provider = LinkedFileRemoteScreenProvider(linkedFileStorage)
        val supporter = Device(provider, linkedFileStorage)
        val communicator = Device(provider, linkedFileStorage)

        val screen = supporter.boards.createBoardSet("Family", 2, 2)
        assertEquals(
            "ok",
            supporter.actions.perform(
                RemoteScreenRequest(
                    action = "connect",
                    screenId = screen.id,
                    provider = "linked_file",
                    location = "/cloud/home.obz",
                    displayName = "home.obz",
                ),
            ).status,
        )
        assertEquals(
            "ok",
            communicator.actions.perform(
                RemoteScreenRequest(
                    action = "open",
                    provider = "linked_file",
                    location = "/cloud/home.obz",
                    displayName = "home.obz",
                ),
            ).status,
        )
        val linked = communicator.boards.listBoardSets().single()
        assertEquals("home.obz", linked.remoteLink?.resolvedDisplayName())

        supporter.boards.renameBoardSet(screen.id, "Family Updated")
        assertTrue(supporter.actions.syncAllAutomatic(force = true))
        assertTrue(communicator.actions.syncAllAutomatic(force = true))
        assertEquals("Family Updated", communicator.boards.getBoardSet(linked.id)?.name)
    }

    @Test
    fun iCloudShareSyncsOwnerAndParticipantsAndReleasesOnDisconnect() = runBlocking {
        val ownerLocation = "icloud://private/WingmateScreens/rec1"
        val participantLocation = "icloud://shared/_owner/WingmateScreens/rec1"
        var record: CloudShareRecord? = CloudShareRecord(revision = null, bytes = null)
        var tag = 0
        val released = mutableListOf<String>()
        val cloud = object : CloudShareScreenStorage {
            override val isSupported = true
            override suspend fun fetch(location: String, includePackage: Boolean) = record
            override suspend fun save(location: String, bytes: ByteArray, expectedRevision: String?): String {
                val current = record ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "missing")
                if (current.revision != expectedRevision) {
                    throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "changed")
                }
                val updated = CloudShareRecord("tag-${++tag}", bytes)
                record = updated
                return updated.revision!!
            }
            override suspend fun release(location: String) {
                released += location
                if (location.startsWith("icloud://private/")) {
                    // Owner disconnecting deletes the shared CloudKit record.
                    record = null
                }
            }
        }
        val provider = CloudShareRemoteScreenProvider(cloud)
        val parent = Device(provider, cloudShareStorage = cloud)
        val child = Device(provider, cloudShareStorage = cloud)
        val viewer = Device(provider, cloudShareStorage = cloud)

        val screen = parent.boards.createBoardSet("School", 2, 2)
        fun request(action: String, location: String, id: String? = null, readOnly: Boolean = false, ownerName: String = "") =
            RemoteScreenRequest(
                action, screenId = id, provider = "icloud_share", location = location,
                readOnly = readOnly, ownerName = ownerName,
            )
        assertEquals("ok", parent.actions.perform(request("connect", ownerLocation, screen.id)).status)
        assertEquals("tag-1", record?.revision)
        assertEquals(RemoteScreenOwnership.OWNER, parent.boards.getBoardSet(screen.id)?.remoteLink?.resolvedOwnership)
        assertEquals("ok", child.actions.perform(request("open", participantLocation, ownerName = "Parent")).status)
        assertEquals("ok", viewer.actions.perform(request("open", participantLocation, readOnly = true, ownerName = "Parent")).status)
        val childScreen = child.boards.listBoardSets().single()
        val viewerScreen = viewer.boards.listBoardSets().single()
        assertEquals("iCloud", childScreen.remoteLink?.resolvedDisplayName())
        assertEquals(RemoteScreenOwnership.SHARED, childScreen.remoteLink?.resolvedOwnership)
        assertEquals("Parent", childScreen.remoteLink?.ownerName)
        assertNull(childScreen.remoteLink?.credentialId)
        assertTrue(viewerScreen.remoteLink?.readOnly == true)

        // A participant with write access edits; owner and view-only participant receive it.
        child.boards.renameBoardSet(childScreen.id, "School (teacher edit)")
        assertTrue(child.actions.syncAllAutomatic(force = true))
        assertTrue(parent.actions.syncAllAutomatic(force = true))
        assertTrue(viewer.actions.syncAllAutomatic(force = true))
        assertEquals("School (teacher edit)", parent.boards.getBoardSet(screen.id)?.name)
        assertEquals("School (teacher edit)", viewer.boards.getBoardSet(viewerScreen.id)?.name)

        // View-only local edits never reach iCloud.
        viewer.boards.renameBoardSet(viewerScreen.id, "Local only")
        assertEquals("ok", viewer.actions.perform(request("sync", participantLocation, viewerScreen.id)).status)
        assertEquals("tag-2", record?.revision)

        // Owner disconnects: deletes the CloudKit record; other devices automatically drop the dead link
        // on their next sync while keeping their local Screen copy intact.
        assertEquals("ok", parent.actions.perform(request("disconnect", ownerLocation, screen.id)).status)
        assertEquals(listOf(ownerLocation), released)
        assertNull(parent.boards.getBoardSet(screen.id)?.remoteLink)

        assertTrue(child.actions.syncAllAutomatic(force = true))
        assertNull(child.boards.getBoardSet(childScreen.id)?.remoteLink)
        assertEquals("School (teacher edit)", child.boards.getBoardSet(childScreen.id)?.name)

        assertTrue(viewer.actions.syncAllAutomatic(force = true))
        assertNull(viewer.boards.getBoardSet(viewerScreen.id)?.remoteLink)
        assertEquals("Local only", viewer.boards.getBoardSet(viewerScreen.id)?.name)
    }

    @Test
    fun cloudDriveSignInCreatesFileAndAnotherDeviceOpensItWithoutExposingTokens() = runBlocking {
        val storage = RemoteStorage()
        val created = mutableListOf<RemoteFileChoice>()
        val files = object : CloudDriveFiles {
            override suspend fun listScreenFiles(kind: RemoteStorageKind, secret: String): List<RemoteFileChoice> {
                assertEquals("token-bundle", secret)
                return created.toList()
            }
            override suspend fun createScreenFile(kind: RemoteStorageKind, secret: String, fileName: String) =
                RemoteFileChoice("drive-file-1", fileName).also { created += it }
            override suspend fun accountLabel(kind: RemoteStorageKind, secret: String) = "parent@example.com"
        }
        val signIn = object : CloudDriveSignIn {
            override fun isSupported(kind: RemoteStorageKind) = kind == RemoteStorageKind.GOOGLE_DRIVE
            override suspend fun signIn(kind: RemoteStorageKind) = "token-bundle"
        }
        val ipadCredentials = MemoryCredentials()
        val ipad = Device(storage, credentials = ipadCredentials, cloudDriveSignIn = signIn, cloudDriveFiles = files)
        val phone = Device(storage, credentials = MemoryCredentials(), cloudDriveSignIn = signIn, cloudDriveFiles = files)
        assertEquals(listOf("google_drive"), ipad.actions.cloudDriveProviders)

        val screen = ipad.boards.createBoardSet("Home", 2, 2)
        fun request(action: String, ref: String = "", id: String? = null, location: String = "") =
            RemoteScreenRequest(action, screenId = id, provider = "google_drive", location = location, accountRef = ref)

        // Linking without signing in is refused; signing in returns only an opaque reference.
        assertEquals("error", ipad.actions.perform(request("connect", id = screen.id)).status)
        val signedIn = ipad.actions.perform(request("sign_in"))
        assertEquals("Signed in as parent@example.com", signedIn.message)
        val ref = assertNotNull(signedIn.accountRef)
        assertFalse(ref.contains("token"))

        assertEquals("ok", ipad.actions.perform(request("connect", ref, screen.id)).status)
        val linked = assertNotNull(ipad.boards.getBoardSet(screen.id)?.remoteLink)
        assertEquals("drive-file-1", linked.location)
        assertEquals("Home.obz", linked.resolvedDisplayName())
        assertEquals("token-bundle", ipadCredentials.values[linked.credentialId])
        assertNotNull(storage.file)

        val phoneRef = assertNotNull(phone.actions.perform(request("sign_in")).accountRef)
        val listed = phone.actions.perform(request("list_files", phoneRef))
        assertEquals(listOf(RemoteFileChoice("drive-file-1", "Home.obz")), listed.files)
        assertEquals("error", phone.actions.perform(request("open", ref = "forged", location = "drive-file-1")).status)
        assertEquals("ok", phone.actions.perform(request("open", phoneRef, location = "drive-file-1")).status)
        assertEquals("Home", phone.boards.listBoardSets().single().name)
    }

    @Test
    fun openRemoteScreenReusesTheSettingsSyncAccountWithoutSigningInAgain() = runBlocking {
        var signIns = 0
        val files = object : CloudDriveFiles {
            override suspend fun listScreenFiles(kind: RemoteStorageKind, secret: String): List<RemoteFileChoice> {
                assertEquals("sync-bundle", secret)
                return listOf(RemoteFileChoice("drive-file-1", "Home.obz"))
            }
            override suspend fun createScreenFile(kind: RemoteStorageKind, secret: String, fileName: String) =
                RemoteFileChoice("drive-file-1", fileName)
            override suspend fun accountLabel(kind: RemoteStorageKind, secret: String) = "parent@example.com"
        }
        val signIn = object : CloudDriveSignIn {
            override fun isSupported(kind: RemoteStorageKind) = kind == RemoteStorageKind.GOOGLE_DRIVE
            override suspend fun signIn(kind: RemoteStorageKind): String {
                signIns++
                return "sync-bundle"
            }
        }
        val device = Device(RemoteStorage(), credentials = MemoryCredentials(), cloudDriveSignIn = signIn, cloudDriveFiles = files)
        assertEquals(emptyList(), device.actions.connectedCloudDrives())

        assertEquals("ok", device.actions.connectCloudSync("google_drive").status)
        assertEquals(1, signIns)
        assertEquals(listOf("google_drive"), device.actions.connectedCloudDrives())

        // "Open remote Screen" uses the connected account: no second sign-in page.
        val signedIn = device.actions.perform(RemoteScreenRequest("sign_in", provider = "google_drive"))
        assertEquals("ok", signedIn.status)
        assertEquals("Signed in as parent@example.com", signedIn.message)
        assertEquals(1, signIns)
        val listed = device.actions.perform(
            RemoteScreenRequest("list_files", provider = "google_drive", accountRef = assertNotNull(signedIn.accountRef)),
        )
        assertEquals(listOf(RemoteFileChoice("drive-file-1", "Home.obz")), listed.files)
    }

    /** A tiny shared Google Drive: file ID -> contents, with revisions and a trash. */
    private class FakeDrive : RemoteScreenStorage, CloudDriveFiles {
        val files = linkedMapOf<String, ByteArray>()
        val trashed = mutableListOf<String>()
        private var counter = 0

        private fun revision(bytes: ByteArray) = bytes.toByteString().sha256().hex()

        override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
            val bytes = files[link.location]?.takeIf { it.isNotEmpty() } ?: return RemoteFileMetadata(null, exists = false)
            return RemoteFileMetadata(revision(bytes))
        }
        override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
            val bytes = files[link.location]?.takeIf { it.isNotEmpty() }
                ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Missing")
            return RemoteFileContent(bytes, revision(bytes), revision(bytes))
        }
        override suspend fun upload(
            link: RemoteScreenLink,
            secret: String?,
            bytes: ByteArray,
            expectedRevision: String?,
            commitMessage: String,
        ): RemoteWriteResult {
            val current = files[link.location] ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Missing")
            if (current.isNotEmpty() && revision(current) != expectedRevision) {
                throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "Changed")
            }
            files[link.location] = bytes
            return RemoteWriteResult(revision(bytes), revision(bytes))
        }
        override suspend fun delete(link: RemoteScreenLink, secret: String?) {
            files.remove(link.location)?.let { trashed += link.location }
        }
        override suspend fun listScreenFiles(kind: RemoteStorageKind, secret: String) =
            files.keys.map { RemoteFileChoice(it, "$it.obz") }
        override suspend fun createScreenFile(kind: RemoteStorageKind, secret: String, fileName: String): RemoteFileChoice {
            val id = "file-${++counter}"
            files[id] = ByteArray(0)
            return RemoteFileChoice(id, fileName)
        }
        override suspend fun accountLabel(kind: RemoteStorageKind, secret: String) = "parent@example.com"
    }

    @Test
    fun deletingAskedPerScreenRemovesItFromDriveOrKeepsItDeletedOnlyHere() = runBlocking {
        val drive = FakeDrive()
        val signIn = object : CloudDriveSignIn {
            override fun isSupported(kind: RemoteStorageKind) = kind == RemoteStorageKind.GOOGLE_DRIVE
            override suspend fun signIn(kind: RemoteStorageKind) = "bundle"
        }
        val ipadCredentials = MemoryCredentials()
        val ipad = Device(drive, credentials = ipadCredentials, cloudDriveSignIn = signIn, cloudDriveFiles = drive)
        val phone = Device(drive, credentials = MemoryCredentials(), cloudDriveSignIn = signIn, cloudDriveFiles = drive)
        val home = ipad.boards.createBoardSet("Home", 2, 2)
        val school = ipad.boards.createBoardSet("School", 2, 2)

        // Settings → Sync pushes both Screens to Drive; the phone pulls them.
        assertEquals("ok", ipad.actions.connectCloudSync("google_drive").status)
        assertEquals(2, drive.files.values.count { it.isNotEmpty() })
        assertEquals("ok", phone.actions.connectCloudSync("google_drive").status)
        assertEquals(setOf("Home", "School"), phone.boards.listBoardSets().map { it.name }.toSet())
        val connectionCredential = assertNotNull(ipad.actions.listSyncConnections().single().credentialId)
        assertTrue(assertNotNull(ipad.boards.getBoardSet(home.id)?.remoteLink).offersRemoteDelete)

        // "Delete only on this device": the file stays in Drive and is not pulled back in.
        assertEquals("ok", ipad.actions.deleteScreen(home.id, deleteRemote = false).status)
        ipad.actions.syncAllAutomatic(force = true)
        assertEquals(listOf("School"), ipad.boards.listBoardSets().map { it.name })
        assertEquals(2, drive.files.size)
        // Pulled Screens share the connection's sign-in; deleting one keeps it.
        assertNotNull(ipadCredentials.values[connectionCredential])

        // "Delete here and in Google Drive": the file goes to the trash and other devices follow.
        val schoolFile = assertNotNull(ipad.boards.getBoardSet(school.id)?.remoteLink).location
        assertEquals("ok", ipad.actions.deleteScreen(school.id, deleteRemote = true).status)
        assertEquals(listOf(schoolFile), drive.trashed)
        assertTrue(ipad.boards.listBoardSets().isEmpty())
        phone.actions.syncAllAutomatic(force = true)
        assertEquals(listOf("Home"), phone.boards.listBoardSets().map { it.name })
    }

    private class MemoryCredentials : RemoteScreenCredentialStorage {
        val values = mutableMapOf<String, String>()
        override val isSupported = true
        override suspend fun read(credentialId: String) = values[credentialId]
        override suspend fun write(credentialId: String, secret: String) {
            values[credentialId] = secret
        }
        override suspend fun delete(credentialId: String) {
            values.remove(credentialId)
        }
    }

    @Test
    fun settingsSyncConnectionAutoPushesAndAutoPullsFromWingmateFolder() = runBlocking {
        val folderFiles = mutableMapOf<String, ByteArray>()
        val folderStorage = object : LinkedRemoteFileStorage {
            override val isSupported = true
            override suspend fun pickExistingFile(): LinkedFileHandle? = null
            override suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle? = null
            override suspend fun listFolderScreenFiles(folderLocation: String): List<RemoteFileChoice> =
                folderFiles.keys
                    .filter { it.startsWith("$folderLocation/Wingmate/") }
                    .map { path -> RemoteFileChoice(path, path.substringAfterLast('/'), ownedByMe = true) }
            override suspend fun createFolderScreenFile(folderLocation: String, fileName: String): RemoteFileChoice {
                val path = "$folderLocation/Wingmate/$fileName"
                folderFiles.putIfAbsent(path, ByteArray(0))
                return RemoteFileChoice(path, fileName, ownedByMe = true)
            }
            override suspend fun readBytes(location: String): ByteArray =
                folderFiles[location] ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Missing")
            override suspend fun writeBytes(location: String, bytes: ByteArray) {
                folderFiles[location] = bytes
            }
        }
        val provider = LinkedFileRemoteScreenProvider(folderStorage)
        val ipad = Device(provider, linkedFileStorage = folderStorage)
        val mac = Device(provider, linkedFileStorage = folderStorage)

        val home = ipad.boards.createBoardSet("Home", 2, 2)
        assertEquals("ok", ipad.actions.connectFolderSync("/shared-drive", "Shared Drive").status)
        assertEquals(1, ipad.actions.listSyncConnections().size)
        assertTrue(folderFiles.containsKey("/shared-drive/Wingmate/Home.obz"))
        assertEquals("/shared-drive/Wingmate/Home.obz", ipad.boards.getBoardSet(home.id)?.remoteLink?.location)

        // Connecting the same folder on Mac automatically pulls Home.obz without manual Open.
        assertEquals("ok", mac.actions.connectFolderSync("/shared-drive", "Shared Drive").status)
        val macScreen = mac.boards.listBoardSets().single()
        assertEquals("Home", macScreen.name)

        // Edits on Mac auto-push and pull onto iPad.
        mac.boards.renameBoardSet(macScreen.id, "Home Updated")
        assertTrue(mac.actions.syncAllAutomatic(force = true))
        assertTrue(ipad.actions.syncAllAutomatic(force = true))
        assertEquals("Home Updated", ipad.boards.getBoardSet(home.id)?.name)

        // Disconnecting in Settings unlinks Screens while keeping the local copy intact.
        val connId = mac.actions.listSyncConnections().single().id
        assertEquals("ok", mac.actions.disconnectSyncConnection(connId).status)
        assertTrue(mac.actions.listSyncConnections().isEmpty())
        assertNull(mac.boards.getBoardSet(macScreen.id)?.remoteLink)
        assertEquals("Home Updated", mac.boards.getBoardSet(macScreen.id)?.name)
    }

    @Test
    fun editingAccessBlocksManagementActionsButAllowsAutomaticSync() = runBlocking {
        val storage = RemoteStorage()
        val device = Device(storage)
        val screen = device.boards.createBoardSet("Home", 2, 2)
        device.editingAccess.configure("1234")
        device.editingAccess.lock()
        assertEquals("locked", device.perform("connect", screen.id))
        assertNull(storage.file)
        assertNull(device.boards.getBoardSet(screen.id)?.remoteLink)
        // sync_auto is non-blocking and allowed while Editing access is locked.
        assertEquals("ok", device.perform("sync_auto"))
    }

    private class Device(
        storage: RemoteScreenStorage,
        linkedFileStorage: LinkedRemoteFileStorage = io.github.jdreioe.wingmate.domain.obf.UnsupportedLinkedRemoteFileStorage,
        backgroundScope: CoroutineScope? = null,
        cloudShareStorage: CloudShareScreenStorage = io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudShareScreenStorage,
        credentials: RemoteScreenCredentialStorage = UnsupportedRemoteScreenCredentialStorage,
        cloudDriveSignIn: CloudDriveSignIn = io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudDriveSignIn,
        cloudDriveFiles: CloudDriveFiles? = null,
    ) {
        private val sets = InMemoryBoardSetRepository()
        private val pages = InMemoryBoardRepository()
        val settingsRepo = InMemorySettingsRepository()
        val boards = BoardSetUseCase(sets, pages, NoopFeatureUsageReporter(), linkedFileStorage = linkedFileStorage)
        val editingAccess = EditingAccessController(DefaultEditingAccessStore(InMemorySecureEditingCredentialStorage()))
        val actions = RemoteScreenActions(
            RemoteScreenSyncUseCase(
                boardSetUseCase = boards,
                boardSetRepository = sets,
                importService = BoardImportService(ObfParser(), pages, sets, TestArchivePicker),
                storage = storage,
                credentials = credentials,
                linkedFileStorage = linkedFileStorage,
                backgroundScope = backgroundScope,
                cloudShareStorage = cloudShareStorage,
                cloudDriveFiles = cloudDriveFiles,
                settingsRepository = settingsRepo,
            ),
            editingAccess,
            linkedFileStorage,
            cloudDriveSignIn,
            cloudDriveFiles,
        )

        suspend fun perform(action: String, id: String? = null) = actions.perform(
            RemoteScreenRequest(action, screenId = id, provider = "linked_file", location = "/sync/home.obz"),
        ).status
    }

    private class RemoteStorage : RemoteScreenStorage {
        var file: RemoteFileContent? = null
            private set

        override suspend fun head(link: RemoteScreenLink, secret: String?) =
            RemoteFileMetadata(file?.revision, exists = file != null)

        override suspend fun download(link: RemoteScreenLink, secret: String?) = checkNotNull(file)

        override suspend fun upload(
            link: RemoteScreenLink,
            secret: String?,
            bytes: ByteArray,
            expectedRevision: String?,
            commitMessage: String,
        ): RemoteWriteResult {
            if (file?.revision != expectedRevision) {
                throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "Revision changed")
            }
            val hash = bytes.toByteString().sha256().hex()
            file = RemoteFileContent(bytes, hash, hash)
            return RemoteWriteResult(hash, hash)
        }
    }

    private object TestArchivePicker : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String): String? = null
        override suspend fun openArchive(path: String): ArchiveReader? = null
        override suspend fun openArchiveBytes(content: ByteArray): ArchiveReader {
            val path = Files.createTempFile("wingmate-remote-test", ".obz")
            Files.write(path, content)
            val zip = java.util.zip.ZipFile(path.toFile())
            return object : ArchiveReader {
                override suspend fun entries() = zip.entries().toList()
                    .map { ArchiveEntry(it.name, it.size, it.compressedSize, it.isDirectory) }
                override suspend fun readEntry(name: String, maxBytes: Long, onChunk: suspend (ByteArray) -> Unit) {
                    onChunk(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                }
                override suspend fun close() {
                    try {
                        zip.close()
                    } finally {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
    }
}
