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
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteWriteResult
import io.github.jdreioe.wingmate.domain.obf.UnsupportedRemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.CloudShareRemoteScreenProvider
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
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
        var record = CloudShareRecord(revision = null, bytes = null)
        var tag = 0
        val released = mutableListOf<String>()
        val cloud = object : CloudShareScreenStorage {
            override val isSupported = true
            override suspend fun fetch(location: String, includePackage: Boolean) = record
            override suspend fun save(location: String, bytes: ByteArray, expectedRevision: String?): String {
                if (record.revision != expectedRevision) {
                    throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "changed")
                }
                record = CloudShareRecord("tag-${++tag}", bytes)
                return record.revision!!
            }
            override suspend fun release(location: String) {
                released += location
            }
        }
        val provider = CloudShareRemoteScreenProvider(cloud)
        val parent = Device(provider, cloudShareStorage = cloud)
        val child = Device(provider, cloudShareStorage = cloud)
        val viewer = Device(provider, cloudShareStorage = cloud)

        val screen = parent.boards.createBoardSet("School", 2, 2)
        fun request(action: String, location: String, id: String? = null, readOnly: Boolean = false) =
            RemoteScreenRequest(action, screenId = id, provider = "icloud_share", location = location, readOnly = readOnly)
        assertEquals("ok", parent.actions.perform(request("connect", ownerLocation, screen.id)).status)
        assertEquals("tag-1", record.revision)
        assertEquals("ok", child.actions.perform(request("open", participantLocation)).status)
        assertEquals("ok", viewer.actions.perform(request("open", participantLocation, readOnly = true)).status)
        val childScreen = child.boards.listBoardSets().single()
        val viewerScreen = viewer.boards.listBoardSets().single()
        assertEquals("iCloud", childScreen.remoteLink?.resolvedDisplayName())
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
        assertEquals("tag-2", record.revision)

        assertEquals("ok", parent.actions.perform(request("disconnect", ownerLocation, screen.id)).status)
        assertEquals(listOf(ownerLocation), released)
        assertNull(parent.boards.getBoardSet(screen.id)?.remoteLink)
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
            ),
            editingAccess,
            linkedFileStorage,
            cloudDriveSignIn,
            cloudDriveFiles,
        )

        suspend fun perform(action: String, id: String? = null) = actions.perform(
            RemoteScreenRequest(action, screenId = id, location = "https://example.test/home.obz"),
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
