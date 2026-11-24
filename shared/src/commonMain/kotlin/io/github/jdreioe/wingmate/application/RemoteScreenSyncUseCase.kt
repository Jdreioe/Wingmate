package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.obf.CloudDriveFiles
import io.github.jdreioe.wingmate.domain.obf.CloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.RemoteConflictResolution
import io.github.jdreioe.wingmate.domain.obf.RemoteFileContent
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenLink
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.github.jdreioe.wingmate.domain.obf.RemoteSyncOutcome
import io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.UnsupportedLinkedRemoteFileStorage
import io.github.jdreioe.wingmate.infrastructure.BoardImportErrorCode
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class RemoteScreenLinkDraft(
    val kind: RemoteStorageKind,
    val location: String,
    val displayName: String = "",
    val branch: String = "",
    val filePath: String = "",
    val username: String = "",
    val secret: String = "",
    val readOnly: Boolean = false,
) {
    fun toLink() = RemoteScreenLink(
        kind = kind,
        readOnly = readOnly || kind == RemoteStorageKind.HTTPS_URL || (kind == RemoteStorageKind.GITHUB && secret.isBlank()),
        location = location.trim(),
        displayName = displayName.trim(),
        branch = branch.trim().ifEmpty { null }.takeIf { kind == RemoteStorageKind.GITHUB },
        filePath = filePath.trim().ifEmpty { null }.takeIf { kind == RemoteStorageKind.GITHUB },
        username = username.trim().ifEmpty { null }.takeIf { kind == RemoteStorageKind.WEBDAV },
        credentialId = if (secret.isEmpty() || kind == RemoteStorageKind.LINKED_FILE || kind == RemoteStorageKind.ICLOUD_SHARE) {
            null
        } else {
            "remote-screen-${Uuid.random()}"
        },
    )
}

/**
 * Exchanges linked Screens as `.obz` Vocabulary packages with user-provided storage
 * (WebDAV, GitHub, read-only HTTPS URL, OS/cloud-synced linked file, or an iCloud share).
 *
 * Supports automatic background pull/push with zero user input as well as explicit sync
 * and three-way conflict resolution.
 */
class RemoteScreenSyncUseCase(
    private val boardSetUseCase: BoardSetUseCase,
    private val boardSetRepository: BoardSetRepository,
    private val importService: BoardImportService,
    private val storage: RemoteScreenStorage,
    private val credentials: RemoteScreenCredentialStorage,
    private val speechCache: BoardSetSpeechCacheUseCase? = null,
    private val linkedFileStorage: LinkedRemoteFileStorage = UnsupportedLinkedRemoteFileStorage,
    private val backgroundScope: CoroutineScope? = null,
    private val cloudShareStorage: CloudShareScreenStorage = UnsupportedCloudShareScreenStorage,
    private val cloudDriveFiles: CloudDriveFiles? = null,
) {
    private val mutex = Mutex()
    private val scheduleMutex = Mutex()
    private val pendingPushJobs = mutableMapOf<String, Job>()
    private val lastAutoSyncAttemptAt = mutableMapOf<String, Long>()
    private var periodicJob: Job? = null

    private val _syncVersion = MutableStateFlow(0L)
    /** Increments whenever a remote sync pulls, connects, disconnects, or marks a conflict. */
    val syncVersion: StateFlow<Long> = _syncVersion.asStateFlow()

    init {
        boardSetUseCase.setOnScreenMutatedListener { boardSetId ->
            scheduleAutoPush(boardSetId)
        }
    }

    /** Schedules a debounced automatic push after a local edit to a linked Screen. */
    fun scheduleAutoPush(boardSetId: String, delayMillis: Long = DEFAULT_AUTO_PUSH_DEBOUNCE_MS) {
        val scope = backgroundScope ?: return
        scope.launch {
            val set = runCatching { boardSetRepository.getBoardSet(boardSetId) }.getOrNull() ?: return@launch
            val link = set.remoteLink ?: return@launch
            if (!link.isWritable || link.hasConflict) return@launch
            scheduleMutex.withLock {
                pendingPushJobs.remove(boardSetId)?.cancel()
                pendingPushJobs[boardSetId] = scope.launch {
                    delay(delayMillis)
                    sync(boardSetId, automatic = true)
                }
            }
        }
    }

    /** Starts a single periodic background sync loop so remote edits arrive with no user input. */
    fun startPeriodicAutoSync(intervalMillis: Long = DEFAULT_AUTO_SYNC_INTERVAL_MS): Job? {
        val scope = backgroundScope ?: return null
        if (periodicJob?.isActive == true) return periodicJob
        periodicJob = scope.launch {
            syncAllAutomatic(minIntervalMillis = 0L, force = true)
            while (isActive) {
                delay(intervalMillis)
                syncAllAutomatic(minIntervalMillis = intervalMillis / 2, force = false)
            }
        }
        return periodicJob
    }

    /**
     * Automatically syncs every linked Screen without requiring user input or Editing access unlock.
     * Pulls remote edits even onto locked Screens (keeping them locked locally) and pushes local edits.
     */
    suspend fun syncAllAutomatic(
        minIntervalMillis: Long = DEFAULT_AUTO_SYNC_THROTTLE_MS,
        force: Boolean = false,
    ): List<RemoteSyncOutcome> {
        val linked = runCatching {
            boardSetRepository.listBoardSets().filter { it.remoteLink != null }
        }.getOrDefault(emptyList())
        if (linked.isEmpty()) return emptyList()
        val outcomes = mutableListOf<RemoteSyncOutcome>()
        for (set in linked) {
            val nowMs = now()
            val lastAttempt = lastAutoSyncAttemptAt[set.id] ?: 0L
            if (!force && nowMs - lastAttempt < minIntervalMillis) continue
            lastAutoSyncAttemptAt[set.id] = nowMs
            outcomes += sync(set.id, automatic = true)
        }
        return outcomes
    }

    internal suspend fun connect(boardSetId: String, draft: RemoteScreenLinkDraft): RemoteSyncOutcome = guarded(boardSetId) {
        val set = requireScreen(boardSetId)
        if (set.remoteLink != null) {
            fail(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Disconnect the existing remote link first")
        }
        if (set.kind != ScreenKind.User) {
            fail(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Only your own Screens can be linked to storage")
        }
        val link = withCreatedCloudFile(draft, set.name).toLink()
        val secret = draft.secret.ifEmpty { null }
        val meta = storage.head(link, secret)
        if (!meta.exists && !link.isWritable) {
            fail(RemoteStorageErrorCode.NOT_FOUND, "Remote file was not found")
        }
        val linked = withNewCredential(link, secret) { saveLink(boardSetId, link) }
        val outcome = if (meta.exists) markConflict(linked, "A Screen already exists at this location")
        else push(linked, secret, revision = null)
        bumpVersion()
        outcome
    }

    /** Google Drive / OneDrive: a blank location means "create a new file for this Screen". */
    private suspend fun withCreatedCloudFile(draft: RemoteScreenLinkDraft, screenName: String): RemoteScreenLinkDraft {
        if (!draft.kind.isCloudDrive || draft.location.isNotBlank()) return draft
        val files = cloudDriveFiles ?: fail(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Cloud drives are not available")
        val secret = draft.secret.ifEmpty { null } ?: fail(RemoteStorageErrorCode.UNAUTHORIZED, "Sign in first")
        val created = files.createScreenFile(draft.kind, secret, "$screenName.obz")
        return RemoteScreenLinkDraft(
            kind = draft.kind,
            location = created.location,
            displayName = draft.displayName.ifBlank { created.name },
            secret = draft.secret,
            readOnly = draft.readOnly,
        )
    }

    internal suspend fun openRemote(draft: RemoteScreenLinkDraft): RemoteSyncOutcome = guarded(null) {
        val link = draft.toLink()
        val secret = draft.secret.ifEmpty { null }
        val remote = storage.download(link, secret)
        val imported = withNewCredential(link, secret) {
            val result = import(remote.bytes, preserveBoardIds = true)
            try {
                val updated = saveBaseline(result.boardSet.id, link, remote.revision, remote.contentHash)
                RemoteSyncOutcome.Pulled(updated, result.warnings.size)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { boardSetUseCase.deleteBoardSet(result.boardSet.id) }
                }
                throw error
            }
        }
        cache(imported.boardSet.id)
        bumpVersion()
        imported
    }

    internal suspend fun disconnect(boardSetId: String): RemoteSyncOutcome = guarded(boardSetId) {
        val set = requireScreen(boardSetId)
        val link = set.remoteLink
        val updated = withContext(NonCancellable) {
            link?.credentialId?.let { runCatching { credentials.delete(it) } }
            if (link?.kind == RemoteStorageKind.LINKED_FILE) {
                runCatching { linkedFileStorage.releaseAccess(link.location) }
            }
            if (link?.kind == RemoteStorageKind.ICLOUD_SHARE) {
                // Owner stops sharing; a participant leaves the share. Local copies stay intact.
                runCatching { cloudShareStorage.release(link.location) }
            }
            saveLink(boardSetId, null)
        }
        bumpVersion()
        RemoteSyncOutcome.Disconnected(updated)
    }

    internal suspend fun sync(boardSetId: String, automatic: Boolean = false): RemoteSyncOutcome = guarded(boardSetId) {
        val set = requireScreen(boardSetId)
        val link = requireLink(set)
        if (link.hasConflict) return@guarded RemoteSyncOutcome.Conflict(set, "Choose which copy to keep")
        val secret = readCredential(link)
        val meta = storage.head(link, secret)
        if (!meta.exists) {
            if (link.isWritable && (link.lastSyncedAt == null || link.kind == RemoteStorageKind.LINKED_FILE)) {
                return@guarded push(set, secret, revision = null)
            }
            fail(RemoteStorageErrorCode.NOT_FOUND, "Remote file was not found")
        }

        val fingerprint = boardSetUseCase.screenFingerprint(boardSetId)
        val localChanged = fingerprint != link.lastSyncedLocalHash
        val remote = if (meta.revision == null || meta.revision != link.lastSyncedRemoteRevision) {
            storage.download(link, secret)
        } else null
        val remoteChanged = remote != null && remote.contentHash != link.lastSyncedRemoteHash
        when {
            remoteChanged && localChanged -> {
                markConflict(set, "This Screen was changed here and in remote storage").also { bumpVersion() }
            }
            remoteChanged -> pull(set, remote, fingerprint, allowLocked = true).also {
                if (it is RemoteSyncOutcome.Pulled) bumpVersion()
            }
            localChanged && link.isWritable -> {
                val revision = remote?.revision ?: meta.revision
                    ?: fail(RemoteStorageErrorCode.CONFLICT, "The storage provider did not supply a file revision")
                push(set, secret, revision)
            }
            localChanged -> RemoteSyncOutcome.ReadOnlyLocalEdits(set)
            else -> RemoteSyncOutcome.UpToDate(saveLink(boardSetId, link.copy(
                lastSyncedRemoteRevision = remote?.revision ?: meta.revision,
                lastSyncedAt = now(),
            )))
        }
    }

    internal suspend fun resolveConflict(
        boardSetId: String,
        resolution: RemoteConflictResolution,
    ): RemoteSyncOutcome = guarded(boardSetId) {
        val set = requireScreen(boardSetId)
        val link = requireLink(set)
        if (!link.hasConflict) fail(RemoteStorageErrorCode.INVALID_CONFIGURATION, "This Screen has no conflict to resolve")
        val secret = readCredential(link)
        val outcome = when (resolution) {
            RemoteConflictResolution.USE_REMOTE -> {
                val fingerprint = boardSetUseCase.screenFingerprint(set.id)
                pull(set, storage.download(link, secret), fingerprint, allowLocked = true)
            }
            RemoteConflictResolution.KEEP_LOCAL -> {
                if (!link.isWritable) fail(RemoteStorageErrorCode.READ_ONLY, "This remote link is read-only")
                val meta = storage.head(link, secret)
                val revision = if (meta.exists) {
                    meta.revision ?: storage.download(link, secret).revision
                        ?: fail(RemoteStorageErrorCode.CONFLICT, "The storage provider did not supply a file revision")
                } else null
                push(set, secret, revision)
            }
            RemoteConflictResolution.SAVE_REMOTE_AS_COPY -> {
                val remote = storage.download(link, secret)
                val copy = import(remote.bytes).boardSet
                val renamed = boardSetUseCase.renameBoardSet(copy.id, "${copy.name} (remote copy)") ?: copy
                // Keep lastSyncedLocalHash as null so local edits remain marked dirty and can
                // never be silently overwritten by a future remote change while remote stays intact.
                val updated = saveLink(
                    set.id,
                    link.copy(
                        lastSyncedRemoteRevision = remote.revision,
                        lastSyncedRemoteHash = remote.contentHash,
                        lastSyncedLocalHash = null,
                        lastSyncedAt = now(),
                        hasConflict = false,
                    ),
                )
                cache(renamed.id)
                RemoteSyncOutcome.CopiedRemote(updated, renamed)
            }
        }
        bumpVersion()
        outcome
    }

    private suspend fun push(set: ObfBoardSet, secret: String?, revision: String?): RemoteSyncOutcome {
        val link = requireLink(set)
        val graph = boardSetUseCase.loadBoardSetGraph(set.id)
            ?: fail(RemoteStorageErrorCode.NOT_FOUND, "Screen not found")
        val export = boardSetUseCase.exportScreenGraph(graph) as? ObzExportResult.Success
            ?: fail(RemoteStorageErrorCode.IO_ERROR, "Screen could not be packaged")
        val result = try {
            storage.upload(link, secret, export.bytes, revision)
        } catch (error: RemoteStorageException) {
            if (error.code == RemoteStorageErrorCode.CONFLICT) {
                return markConflict(set, "The remote file changed while saving").also { bumpVersion() }
            }
            throw error
        }
        return RemoteSyncOutcome.Pushed(saveBaseline(
            set.id, link, result.revision, result.contentHash, boardSetUseCase.screenFingerprint(graph),
        ))
    }

    private suspend fun pull(
        set: ObfBoardSet,
        remote: RemoteFileContent,
        fingerprint: String?,
        allowLocked: Boolean,
    ): RemoteSyncOutcome {
        val link = requireLink(set)
        val current = requireScreen(set.id)
        if (!allowLocked && current.isLocked) return RemoteSyncOutcome.LockedUpdateAvailable(current)
        val imported = importService.replaceBoardSetFromBytesResult(
            targetBoardSetId = set.id,
            content = remote.bytes,
            allowLocked = allowLocked,
        ) {
            val latest = requireScreen(set.id)
            (allowLocked || !latest.isLocked) &&
                latest.remoteLink == link &&
                boardSetUseCase.screenFingerprint(set.id) == fingerprint
        }
        if (imported is BoardImportResult.Failure && imported.code == BoardImportErrorCode.SCREEN_CHANGED) {
            val latest = requireScreen(set.id)
            if (!allowLocked && latest.isLocked) return RemoteSyncOutcome.LockedUpdateAvailable(latest)
            return markConflict(latest, "This Screen changed while downloading the remote copy")
        }
        val result = imported.requireSuccess()
        val updated = saveBaseline(set.id, link, remote.revision, remote.contentHash)
        cache(set.id)
        return RemoteSyncOutcome.Pulled(updated, result.warnings.size)
    }

    private suspend fun import(bytes: ByteArray, preserveBoardIds: Boolean = false) =
        importService.importBoardSetFromBytesResult(bytes, preserveBoardIds, remotePackage = true).requireSuccess()

    private fun BoardImportResult.requireSuccess(): BoardImportResult.Success = when (this) {
        is BoardImportResult.Success -> this
        is BoardImportResult.Failure -> fail(RemoteStorageErrorCode.IO_ERROR, "Remote Screen could not be imported (${code.name})")
        BoardImportResult.Cancelled -> throw CancellationException("Remote Screen import cancelled")
    }

    private suspend fun saveBaseline(
        id: String,
        link: RemoteScreenLink,
        revision: String?,
        remoteHash: String,
        localHash: String? = null,
    ): ObfBoardSet = saveLink(id, link.copy(
        lastSyncedRemoteRevision = revision,
        lastSyncedRemoteHash = remoteHash,
        lastSyncedLocalHash = localHash ?: boardSetUseCase.screenFingerprint(id),
        lastSyncedAt = now(),
        hasConflict = false,
    ))

    private suspend fun markConflict(set: ObfBoardSet, message: String): RemoteSyncOutcome.Conflict {
        val updated = saveLink(set.id, requireLink(set).copy(hasConflict = true))
        return RemoteSyncOutcome.Conflict(updated, message)
    }

    private suspend fun saveLink(id: String, link: RemoteScreenLink?): ObfBoardSet =
        requireScreen(id).copy(remoteLink = link).also { boardSetRepository.saveBoardSet(it) }

    private suspend fun requireScreen(id: String): ObfBoardSet =
        boardSetRepository.getBoardSet(id) ?: fail(RemoteStorageErrorCode.NOT_FOUND, "Screen not found")

    private fun requireLink(set: ObfBoardSet): RemoteScreenLink =
        set.remoteLink ?: fail(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Screen has no remote link")

    private suspend fun readCredential(link: RemoteScreenLink): String? = link.credentialId?.let {
        credentials.read(it) ?: fail(RemoteStorageErrorCode.UNAUTHORIZED, "Credential unavailable. Disconnect and reconnect this Screen")
    }

    private suspend fun <T> withNewCredential(link: RemoteScreenLink, secret: String?, operation: suspend () -> T): T {
        val id = link.credentialId ?: return operation()
        if (!credentials.isSupported) {
            fail(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Secure credential storage is unavailable on this device")
        }
        try {
            credentials.write(id, checkNotNull(secret))
            return operation()
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { credentials.delete(id) } }
            throw error
        }
    }

    private suspend fun cache(id: String) {
        try {
            speechCache?.cacheBoardSet(id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The Screen was already saved. Speech caching is best effort.
        }
    }

    private suspend fun guarded(id: String?, operation: suspend () -> RemoteSyncOutcome): RemoteSyncOutcome =
        mutex.withLock {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteStorageException) {
                if (error.code == RemoteStorageErrorCode.NETWORK_UNAVAILABLE) {
                    RemoteSyncOutcome.Offline(id, error.message ?: "Offline — changes stay on this device")
                } else {
                    RemoteSyncOutcome.Error(id, error.code, error.message.orEmpty())
                }
            } catch (error: Exception) {
                val detail = error.message?.trim()?.take(180)?.takeIf(String::isNotEmpty)
                RemoteSyncOutcome.Error(
                    id,
                    RemoteStorageErrorCode.IO_ERROR,
                    detail ?: "Remote Screen operation failed",
                )
            }
        }

    private fun bumpVersion() {
        _syncVersion.update { it + 1L }
    }

    private fun fail(code: RemoteStorageErrorCode, message: String): Nothing = throw RemoteStorageException(code, message)

    private fun now() = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val DEFAULT_AUTO_PUSH_DEBOUNCE_MS = 1_500L
        const val DEFAULT_AUTO_SYNC_THROTTLE_MS = 15_000L
        const val DEFAULT_AUTO_SYNC_INTERVAL_MS = 45_000L
    }
}
