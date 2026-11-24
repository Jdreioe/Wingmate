package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.CloudDriveFiles
import io.github.jdreioe.wingmate.domain.obf.CloudDriveSignIn
import io.github.jdreioe.wingmate.domain.obf.LinkedFileHandle
import io.github.jdreioe.wingmate.domain.obf.RemoteFileChoice
import io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudDriveSignIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteConflictResolution
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.github.jdreioe.wingmate.domain.obf.RemoteSyncOutcome
import io.github.jdreioe.wingmate.domain.obf.UnsupportedLinkedRemoteFileStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

class KeyedRemoteScreenCredentialStorage(
    private val entry: (String) -> SecureEditingCredentialStorage,
) : RemoteScreenCredentialStorage {
    override val isSupported get() = entry("remote-screen").isSupported
    override suspend fun read(credentialId: String) = entry(credentialId).read()
    override suspend fun write(credentialId: String, secret: String) = entry(credentialId).write(secret)
    override suspend fun delete(credentialId: String) = entry(credentialId).delete()
}

@Serializable
class RemoteScreenRequest(
    val action: String,
    val screenId: String? = null,
    val provider: String = "webdav",
    val location: String = "",
    val displayName: String = "",
    val username: String = "",
    val secret: String = "",
    val branch: String = "",
    val filePath: String = "",
    /** Set by the platform when the storage itself grants view-only access (e.g. a read-only iCloud share). */
    val readOnly: Boolean = false,
    /** Reference to a Google Drive / OneDrive sign-in returned by `sign_in`. Tokens never leave Kotlin. */
    val accountRef: String = "",
)

@Serializable
data class RemoteScreenResult(
    val status: String,
    val message: String,
    /** Set by `sign_in`; pass it back in later requests for the same account. */
    val accountRef: String? = null,
    /** Set by `list_files`. */
    val files: List<RemoteFileChoice> = emptyList(),
) {
    constructor(status: String, message: String) : this(status, message, null, emptyList())
}

class RemoteScreenActions(
    private val sync: RemoteScreenSyncUseCase,
    private val editingAccess: EditingAccessController,
    private val linkedFileStorage: LinkedRemoteFileStorage = UnsupportedLinkedRemoteFileStorage,
    private val cloudDriveSignIn: CloudDriveSignIn = UnsupportedCloudDriveSignIn,
    private val cloudDriveFiles: CloudDriveFiles? = null,
) {
    /** Signed-in cloud drive accounts waiting to be linked, keyed by an opaque reference. */
    private val pendingAccounts = mutableMapOf<String, Pair<RemoteStorageKind, String>>()
    private val pendingMutex = Mutex()

    /** Wire values of the cloud drives this build can sign in to (`google_drive`, `onedrive`). */
    val cloudDriveProviders: List<String>
        get() = listOf(RemoteStorageKind.GOOGLE_DRIVE, RemoteStorageKind.ONEDRIVE)
            .filter { cloudDriveFiles != null && cloudDriveSignIn.isSupported(it) }
            .map { it.wireValue }

    val syncVersion: StateFlow<Long> get() = sync.syncVersion

    val isLinkedFileSupported: Boolean get() = linkedFileStorage.isSupported

    fun startAutoSync() {
        sync.startPeriodicAutoSync()
    }

    /**
     * Runs automatic background sync for all linked Screens without requiring Editing access unlock.
     * Returns true if any linked Screen changed locally or entered a conflict state.
     */
    suspend fun syncAllAutomatic(force: Boolean = false): Boolean {
        val outcomes = sync.syncAllAutomatic(force = force)
        return outcomes.any {
            it is RemoteSyncOutcome.Pulled ||
                it is RemoteSyncOutcome.Pushed ||
                it is RemoteSyncOutcome.Conflict
        }
    }

    suspend fun pickExistingLinkedFile(): LinkedFileHandle? = linkedFileStorage.pickExistingFile()

    suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle? =
        linkedFileStorage.createLinkedFile(suggestedFileName)

    suspend fun perform(request: RemoteScreenRequest): RemoteScreenResult = try {
        if (request.action == "sync_auto") {
            val changed = syncAllAutomatic(force = false)
            RemoteScreenResult("ok", if (changed) "Screens synced" else "Screens are up to date")
        } else {
            performAuthorized(request)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val detail = error.message?.trim()?.take(180)?.takeIf(String::isNotEmpty)
        RemoteScreenResult("error", detail ?: "The remote Screen operation failed")
    }

    private suspend fun performAuthorized(request: RemoteScreenRequest): RemoteScreenResult {
        if (editingAccess.requiresUnlock()) {
            return RemoteScreenResult("locked", "Unlock Editing access before managing remote Screens")
        }
        val id = request.screenId
        when (request.action) {
            "sign_in" -> return signIn(request)
            "list_files" -> return listFiles(request)
        }
        val outcome = when (request.action) {
            "open", "connect" -> {
                val kind = RemoteStorageKind.fromWireValue(request.provider)
                if (kind == null) {
                    return invalidRequest()
                }
                val secret = if (kind.isCloudDrive) {
                    account(request.accountRef, kind) ?: return signInFirst()
                } else {
                    request.secret
                }
                val draft = RemoteScreenLinkDraft(
                    kind = kind,
                    location = request.location,
                    displayName = request.displayName,
                    username = request.username,
                    secret = secret,
                    branch = request.branch,
                    filePath = request.filePath,
                    readOnly = request.readOnly,
                )
                if (request.action == "connect" && id != null) sync.connect(id, draft)
                else if (request.action == "open" && id == null) sync.openRemote(draft)
                else return invalidRequest()
            }
            "sync" -> sync.sync(id ?: return invalidRequest())
            "disconnect" -> sync.disconnect(id ?: return invalidRequest())
            else -> {
                val resolution = RemoteConflictResolution.fromWireValue(request.action) ?: return invalidRequest()
                sync.resolveConflict(id ?: return invalidRequest(), resolution)
            }
        }
        return when (outcome) {
            is RemoteSyncOutcome.UpToDate -> RemoteScreenResult("ok", "Screen is up to date")
            is RemoteSyncOutcome.Pulled -> RemoteScreenResult(
                "ok",
                if (outcome.warningsCount == 0) "Remote Screen downloaded"
                else "Remote Screen downloaded with ${outcome.warningsCount} import warnings",
            )
            is RemoteSyncOutcome.Pushed -> RemoteScreenResult("ok", "Screen saved to remote storage")
            is RemoteSyncOutcome.Conflict -> RemoteScreenResult(
                if (outcome.boardSet.remoteLink?.isWritable == true) "conflict" else "read_only_conflict",
                outcome.message,
            )
            is RemoteSyncOutcome.CopiedRemote -> RemoteScreenResult("ok", "Remote Screen saved as a separate copy")
            is RemoteSyncOutcome.LockedUpdateAvailable -> RemoteScreenResult("error", "Unlock the Screen to apply the remote update")
            is RemoteSyncOutcome.Disconnected -> RemoteScreenResult("ok", "Remote link removed")
            is RemoteSyncOutcome.ReadOnlyLocalEdits -> RemoteScreenResult("ok", "Local edits stay on this device. This subscription is read-only")
            is RemoteSyncOutcome.Offline -> RemoteScreenResult("offline", outcome.message)
            is RemoteSyncOutcome.Error -> RemoteScreenResult("error", outcome.message)
        }
    }

    private suspend fun signIn(request: RemoteScreenRequest): RemoteScreenResult {
        val kind = RemoteStorageKind.fromWireValue(request.provider)?.takeIf { it.isCloudDrive } ?: return invalidRequest()
        if (!cloudDriveSignIn.isSupported(kind)) {
            return RemoteScreenResult("error", "Sign-in for this storage is not set up in this build of Wingmate")
        }
        val bundle = cloudDriveSignIn.signIn(kind) ?: return RemoteScreenResult("cancelled", "Sign-in was cancelled")
        val label = runCatching { cloudDriveFiles?.accountLabel(kind, bundle) }.getOrNull()
        val ref = Uuid.random().toString()
        pendingMutex.withLock { pendingAccounts[ref] = kind to bundle }
        return RemoteScreenResult(
            status = "ok",
            message = label?.let { "Signed in as $it" } ?: "Signed in",
            accountRef = ref,
            files = emptyList(),
        )
    }

    private suspend fun listFiles(request: RemoteScreenRequest): RemoteScreenResult {
        val kind = RemoteStorageKind.fromWireValue(request.provider)?.takeIf { it.isCloudDrive } ?: return invalidRequest()
        val files = cloudDriveFiles ?: return invalidRequest()
        val bundle = account(request.accountRef, kind) ?: return signInFirst()
        val choices = files.listScreenFiles(kind, bundle)
        return RemoteScreenResult(
            status = "ok",
            message = if (choices.isEmpty()) "No Screen files found. Link a Screen from another device first" else "",
            accountRef = request.accountRef,
            files = choices,
        )
    }

    private suspend fun account(ref: String, kind: RemoteStorageKind): String? = pendingMutex.withLock {
        pendingAccounts[ref]?.takeIf { it.first == kind }?.second
    }

    private fun signInFirst() = RemoteScreenResult("error", "Sign in first")

    private fun invalidRequest() = RemoteScreenResult("error", "Choose a valid remote Screen action")
}
