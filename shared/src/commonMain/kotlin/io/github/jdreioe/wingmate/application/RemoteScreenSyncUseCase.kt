package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.obf.CloudDriveFiles
import io.github.jdreioe.wingmate.domain.obf.CloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.RemoteConflictResolution
import io.github.jdreioe.wingmate.domain.obf.RemoteFileChoice
import io.github.jdreioe.wingmate.domain.obf.RemoteFileContent
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenLink
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenOwnership
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.github.jdreioe.wingmate.domain.obf.RemoteSyncOutcome
import io.github.jdreioe.wingmate.domain.obf.SyncConnection
import io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.UnsupportedLinkedRemoteFileStorage
import io.github.jdreioe.wingmate.infrastructure.BoardImportErrorCode
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.CloudDriveFileOperations
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
    val secret: String = "",
    val readOnly: Boolean = false,
    val ownership: RemoteScreenOwnership? = null,
    val ownerName: String? = null,
) {
    fun toLink() = RemoteScreenLink(
        kind = kind,
        readOnly = readOnly,
        location = location.trim(),
        displayName = displayName.trim(),
        credentialId = if (secret.isEmpty() || !kind.isCloudDrive) {
            null
        } else {
            "remote-screen-${Uuid.random()}"
        },
        ownership = ownership ?: when {
            kind == RemoteStorageKind.ICLOUD_SHARE && location.trim().startsWith("icloud://private/") -> RemoteScreenOwnership.OWNER
            kind == RemoteStorageKind.ICLOUD_SHARE && location.trim().startsWith("icloud://shared/") -> RemoteScreenOwnership.SHARED
            else -> null
        },
        ownerName = ownerName?.trim()?.takeIf(String::isNotEmpty),
    )
}

/**
 * Exchanges linked Screens as `.obz` Vocabulary packages with user-provided storage
 * (Google Drive, OneDrive, iCloud sharing, or an OS/cloud-synced linked file).
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
    private val settingsRepository: SettingsRepository? = null,
) {
    private val mutex = Mutex()
    private val scheduleMutex = Mutex()
    private val pendingPushJobs = mutableMapOf<String, Job>()
    private val lastAutoSyncAttemptAt = mutableMapOf<String, Long>()
    private var lastConnectionsSyncAt: Long = 0L
    private var periodicJob: Job? = null

    private val _syncVersion = MutableStateFlow(0L)
    /** Increments whenever a remote sync pulls, connects, disconnects, or marks a conflict. */
    val syncVersion: StateFlow<Long> = _syncVersion.asStateFlow()

    init {
        boardSetUseCase.setOnScreenMutatedListener { boardSetId ->
            scheduleAutoPush(boardSetId)
        }
        boardSetUseCase.setOnScreenDeletingListener { set -> rememberDeletedOnlyHere(set) }
    }

    /** Guards Sync connection bookkeeping. Separate from [mutex]: deletes can run while a sync holds it. */
    private val connectionsMutex = Mutex()
    /** Screens whose remote file is already gone (deleted with it, or removed elsewhere). */
    private val remoteAlreadyDeleted = mutableSetOf<String>()

    /**
     * A linked Screen deleted only on this device: remember its file on the owning Sync connection so
     * automatic folder pull does not bring it back.
     */
    private suspend fun rememberDeletedOnlyHere(set: ObfBoardSet) {
        scheduleMutex.withLock { pendingPushJobs.remove(set.id)?.cancel() }
        val link = set.remoteLink ?: return
        val repo = settingsRepository ?: return
        connectionsMutex.withLock {
            if (remoteAlreadyDeleted.remove(set.id)) return
            val current = repo.get()
            val owner = current.syncConnections.firstOrNull { it.owns(link) } ?: return
            val updated = current.syncConnections.map { conn ->
                if (conn.id != owner.id) conn
                else conn.copy(ignoredLocations = (conn.ignoredLocations + link.location).distinct().takeLast(MAX_IGNORED_LOCATIONS))
            }
            repo.update(current.copy(syncConnections = updated))
        }
    }

    private fun SyncConnection.owns(link: RemoteScreenLink): Boolean {
        if (credentialId != null && link.credentialId == credentialId) return true
        if (kind != RemoteStorageKind.LINKED_FILE || link.kind != RemoteStorageKind.LINKED_FILE) return false
        val folderPrefix = folderLocation.removePrefix("ios-folder-bookmark://")
        return link.location.startsWith("ios-folder-file://$folderPrefix/") || link.location.startsWith(folderLocation)
    }

    /**
     * Deletes a Screen. With [deleteRemote], its Google Drive / OneDrive file goes to the provider's
     * trash first; if that fails the local Screen is kept so nothing is lost.
     */
    internal suspend fun deleteScreen(boardSetId: String, deleteRemote: Boolean): RemoteSyncOutcome = guarded(boardSetId) {
        val set = requireScreen(boardSetId)
        val link = set.remoteLink
        if (deleteRemote) {
            if (link == null || !link.offersRemoteDelete) {
                fail(RemoteStorageErrorCode.INVALID_CONFIGURATION, "This Screen's file cannot be deleted from Wingmate")
            }
            storage.delete(link, readCredential(link))
            connectionsMutex.withLock { remoteAlreadyDeleted += boardSetId }
        }
        boardSetUseCase.deleteBoardSet(boardSetId)
        bumpVersion()
        RemoteSyncOutcome.Deleted(boardSetId, remoteDeleted = deleteRemote)
    }

    /** Schedules a debounced automatic push after a local edit to a linked or synced Screen. */
    fun scheduleAutoPush(boardSetId: String, delayMillis: Long = DEFAULT_AUTO_PUSH_DEBOUNCE_MS) {
        val scope = backgroundScope ?: return
        scope.launch {
            val set = runCatching { boardSetRepository.getBoardSet(boardSetId) }.getOrNull() ?: return@launch
            val link = set.remoteLink
            val hasSyncConn = link == null && set.kind == ScreenKind.User && enabledConnections().isNotEmpty()
            if (link == null && !hasSyncConn) return@launch
            if (link != null && (!link.isWritable || link.hasConflict)) return@launch
            scheduleMutex.withLock {
                pendingPushJobs.remove(boardSetId)?.cancel()
                pendingPushJobs[boardSetId] = scope.launch {
                    delay(delayMillis)
                    if (link != null) {
                        sync(boardSetId, automatic = true)
                    } else {
                        syncAllAutomatic(minIntervalMillis = 0L, force = true)
                    }
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
        val outcomes = mutableListOf<RemoteSyncOutcome>()
        val linked = runCatching {
            boardSetRepository.listBoardSets().filter { it.remoteLink != null }
        }.getOrDefault(emptyList())
        for (set in linked) {
            val nowMs = now()
            val lastAttempt = lastAutoSyncAttemptAt[set.id] ?: 0L
            if (!force && nowMs - lastAttempt < minIntervalMillis) continue
            lastAutoSyncAttemptAt[set.id] = nowMs
            outcomes += sync(set.id, automatic = true)
        }
        val connections = enabledConnections()
        if (connections.isNotEmpty()) {
            val nowMs = now()
            if (force || nowMs - lastConnectionsSyncAt >= minIntervalMillis) {
                lastConnectionsSyncAt = nowMs
                outcomes += syncConfiguredConnections(connections)
            }
        }
        return outcomes
    }

    /** Returns all configured Sync connections stored in Settings. */
    suspend fun listConnections(): List<SyncConnection> =
        runCatching { settingsRepository?.get()?.syncConnections }.getOrNull().orEmpty()

    /**
     * The saved account of an enabled Settings → Sync connection for [kind], so other remote Screen
     * actions can reuse it instead of asking the person to sign in again.
     */
    internal suspend fun cloudConnectionAccount(kind: RemoteStorageKind): Pair<SyncConnection, String>? {
        val connection = listConnections().firstOrNull { it.enabled && it.kind == kind && it.credentialId != null }
            ?: return null
        val secret = runCatching { credentials.read(connection.credentialId!!) }.getOrNull() ?: return null
        return connection to secret
    }

    /** Registers a Google Drive or OneDrive sync connection pointing at its `Wingmate` folder. */
    internal suspend fun addCloudConnection(
        kind: RemoteStorageKind,
        secret: String,
        accountLabel: String?,
        folderLocation: String,
        folderName: String,
    ): SyncConnection {
        require(kind.isCloudDrive) { "Expected a cloud drive kind" }
        val repo = settingsRepository
            ?: fail(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Settings storage is unavailable")
        if (!credentials.isSupported) {
            fail(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Secure credential storage is unavailable on this device")
        }
        val credId = "${SyncConnection.CREDENTIAL_PREFIX}${Uuid.random()}"
        credentials.write(credId, secret)
        val connection = SyncConnection(
            id = Uuid.random().toString(),
            kind = kind,
            accountLabel = accountLabel?.trim().orEmpty(),
            folderName = folderName.ifBlank { SyncConnection.WINGMATE_SYNC_FOLDER_NAME },
            folderLocation = folderLocation.trim(),
            credentialId = credId,
            enabled = true,
        )
        try {
            val current = repo.get()
            val previousSameKind = current.syncConnections.filter { it.kind == kind }
            val updatedList = current.syncConnections.filter { it.kind != kind } + connection
            repo.update(current.copy(syncConnections = updatedList))
            // Clean up replaced credentials of the same provider kind.
            previousSameKind.forEach { old ->
                old.credentialId?.let { runCatching { credentials.delete(it) } }
            }
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { credentials.delete(credId) } }
            throw error
        }
        return connection
    }

    /** Registers an OS folder sync connection whose `Wingmate` subfolder auto-pulls/pushes Screens. */
    internal suspend fun addFolderConnection(folderLocation: String, folderName: String): SyncConnection {
        val repo = settingsRepository
            ?: fail(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Settings storage is unavailable")
        val cleanLocation = folderLocation.trim()
        if (cleanLocation.isEmpty()) {
            fail(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Choose a folder first")
        }
        val cleanName = folderName.trim().ifBlank { SyncConnection.WINGMATE_SYNC_FOLDER_NAME }
        val connection = SyncConnection(
            id = Uuid.random().toString(),
            kind = RemoteStorageKind.LINKED_FILE,
            accountLabel = cleanName,
            folderName = SyncConnection.WINGMATE_SYNC_FOLDER_NAME,
            folderLocation = cleanLocation,
            credentialId = null,
            enabled = true,
        )
        val current = repo.get()
        val updatedList = current.syncConnections.filterNot {
            it.kind == RemoteStorageKind.LINKED_FILE && it.folderLocation == cleanLocation
        } + connection
        repo.update(current.copy(syncConnections = updatedList))
        return connection
    }

    /** Removes a Sync connection from Settings, deletes its credential/bookmark, and unlinks its Screens. */
    internal suspend fun removeConnection(connectionId: String) = mutex.withLock {
        val repo = settingsRepository ?: return@withLock
        val current = repo.get()
        val target = current.syncConnections.firstOrNull { it.id == connectionId } ?: return@withLock
        withContext(NonCancellable) {
            repo.update(current.copy(syncConnections = current.syncConnections.filterNot { it.id == connectionId }))
            // Unlink Screens owned by this connection (keep every local Screen copy intact).
            val sets = runCatching { boardSetRepository.listBoardSets() }.getOrDefault(emptyList())
            val folderPrefix = target.folderLocation.removePrefix("ios-folder-bookmark://")
            for (set in sets) {
                val link = set.remoteLink ?: continue
                val matchesCredential = target.credentialId != null && link.credentialId == target.credentialId
                val matchesFolder = target.kind == RemoteStorageKind.LINKED_FILE && link.kind == RemoteStorageKind.LINKED_FILE && (
                    link.location.startsWith("ios-folder-file://$folderPrefix/") ||
                        link.location.startsWith(target.folderLocation)
                    )
                if (matchesCredential || matchesFolder) {
                    runCatching { boardSetRepository.saveBoardSet(set.copy(remoteLink = null)) }
                }
            }
            target.credentialId?.let { runCatching { credentials.delete(it) } }
            if (target.kind == RemoteStorageKind.LINKED_FILE) {
                runCatching { linkedFileStorage.releaseAccess(target.folderLocation) }
            }
            bumpVersion()
        }
    }

    private suspend fun enabledConnections(): List<SyncConnection> =
        listConnections().filter { it.enabled }

    /**
     * Auto-pulls any unimported `.obz` Screens from each connected `Wingmate` folder and
     * auto-pushes any unlinked local user Screens into the primary connected `Wingmate` folder.
     */
    private suspend fun syncConfiguredConnections(connections: List<SyncConnection>): List<RemoteSyncOutcome> {
        val outcomes = mutableListOf<RemoteSyncOutcome>()
        val knownNamesByConnection = mutableMapOf<String, MutableSet<String>>()

        // 1. Auto-pull newly discovered .obz Screens from every connected folder.
        for (conn in connections) {
            val secret = conn.credentialId?.let { runCatching { credentials.read(it) }.getOrNull() }
            if (conn.kind.isCloudDrive && secret == null) continue
            val files = runCatching {
                when {
                    conn.kind.isCloudDrive -> cloudDriveFiles?.listScreenFiles(
                        conn.kind,
                        secret!!,
                        conn.folderLocation.ifBlank { null },
                    ).orEmpty()
                    conn.kind == RemoteStorageKind.LINKED_FILE ->
                        linkedFileStorage.listFolderScreenFiles(conn.folderLocation)
                    else -> emptyList()
                }
            }.getOrNull() ?: continue

            knownNamesByConnection[conn.id] = files.mapTo(mutableSetOf()) { it.name.lowercase() }
            val existingLocations = runCatching {
                boardSetRepository.listBoardSets().mapNotNull { it.remoteLink?.location }.toSet()
            }.getOrDefault(emptySet())

            for (choice in files) {
                if (choice.location in existingLocations || choice.location in conn.ignoredLocations) continue
                val outcome = pullNewFromConnection(conn, choice, secret)
                if (outcome != null) outcomes += outcome
            }
        }

        // 2. Auto-push unlinked local user Screens into the first connected folder.
        val primary = connections.firstOrNull() ?: return outcomes
        val primarySecret = primary.credentialId?.let { runCatching { credentials.read(it) }.getOrNull() }
        if (primary.kind.isCloudDrive && primarySecret == null) return outcomes
        val usedNames = knownNamesByConnection[primary.id] ?: mutableSetOf()
        val unlinkedUserScreens = runCatching {
            boardSetRepository.listBoardSets().filter { it.kind == ScreenKind.User && it.remoteLink == null }
        }.getOrDefault(emptyList())
        for (screen in unlinkedUserScreens) {
            val outcome = pushUnlinkedToConnection(primary, screen, primarySecret, usedNames)
            if (outcome != null) outcomes += outcome
        }
        return outcomes
    }

    private suspend fun pullNewFromConnection(
        conn: SyncConnection,
        choice: RemoteFileChoice,
        secret: String?,
    ): RemoteSyncOutcome? = guarded(null) {
        val link = RemoteScreenLink(
            kind = conn.kind,
            location = choice.location,
            displayName = choice.name,
            credentialId = conn.credentialId,
            ownership = if (choice.ownedByMe == false) RemoteScreenOwnership.SHARED else RemoteScreenOwnership.OWNER,
            ownerName = choice.ownerName,
        )
        // Skip empty 0-byte placeholders that another device hasn't finished uploading yet.
        val meta = storage.head(link, secret)
        if (!meta.exists) return@guarded RemoteSyncOutcome.UpToDate(
            ObfBoardSet(id = "", name = "", rootBoardId = "", createdAt = 0L, updatedAt = 0L),
        )
        val remote = storage.download(link, secret)
        val result = import(remote.bytes, preserveBoardIds = true)
        val updated = try {
            saveBaseline(result.boardSet.id, link, remote.revision, remote.contentHash)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { boardSetUseCase.deleteBoardSet(result.boardSet.id) }
            }
            throw error
        }
        cache(updated.id)
        bumpVersion()
        RemoteSyncOutcome.Pulled(updated, result.warnings.size)
    }.takeUnless { it is RemoteSyncOutcome.UpToDate && it.boardSet.id.isEmpty() }

    private suspend fun pushUnlinkedToConnection(
        conn: SyncConnection,
        screen: ObfBoardSet,
        secret: String?,
        usedNames: MutableSet<String>,
    ): RemoteSyncOutcome? = guarded(screen.id) {
        val latest = boardSetRepository.getBoardSet(screen.id) ?: return@guarded RemoteSyncOutcome.UpToDate(screen)
        if (latest.remoteLink != null) return@guarded RemoteSyncOutcome.UpToDate(latest)
        val fileName = uniqueObzFileName(latest.name, usedNames)
        val created = when {
            conn.kind.isCloudDrive -> {
                val files = cloudDriveFiles
                    ?: fail(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Cloud drives are not available")
                files.createScreenFile(conn.kind, secret!!, fileName, conn.folderLocation.ifBlank { null })
            }
            conn.kind == RemoteStorageKind.LINKED_FILE ->
                linkedFileStorage.createFolderScreenFile(conn.folderLocation, fileName)
            else -> fail(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Unsupported sync connection")
        }
        usedNames += created.name.lowercase()
        val link = RemoteScreenLink(
            kind = conn.kind,
            location = created.location,
            displayName = created.name,
            credentialId = conn.credentialId,
            ownership = RemoteScreenOwnership.OWNER,
        )
        val linked = saveLink(latest.id, link)
        val outcome = push(linked, secret, revision = null)
        bumpVersion()
        outcome
    }

    private fun uniqueObzFileName(screenName: String, usedLowerNames: MutableSet<String>): String {
        val baseFile = CloudDriveFileOperations.sanitizeFileName(screenName)
        if (baseFile.lowercase() !in usedLowerNames) return baseFile
        val stem = baseFile.removeSuffix(".obz")
        var n = 2
        while (true) {
            val candidate = "$stem ($n).obz"
            if (candidate.lowercase() !in usedLowerNames) return candidate
            n++
        }
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
            ownership = draft.ownership ?: RemoteScreenOwnership.OWNER,
            ownerName = draft.ownerName,
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
            val sharedCredIds = listConnections().mapNotNull { it.credentialId }.toSet()
            link?.credentialId?.takeIf { it !in sharedCredIds }?.let { runCatching { credentials.delete(it) } }
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
            if (link.kind.isCloudDrive && link.lastSyncedAt != null) {
                // The file was deleted in Google Drive / OneDrive (or by another device). Follow the
                // delete unless this copy has edits that were never uploaded; those are kept unlinked.
                if (boardSetUseCase.screenFingerprint(boardSetId) == link.lastSyncedLocalHash) {
                    connectionsMutex.withLock { remoteAlreadyDeleted += boardSetId }
                    boardSetUseCase.deleteBoardSet(boardSetId)
                    bumpVersion()
                    return@guarded RemoteSyncOutcome.Deleted(boardSetId, remoteDeleted = true)
                }
                val disconnected = saveLink(boardSetId, null)
                bumpVersion()
                return@guarded RemoteSyncOutcome.Disconnected(disconnected)
            }
            if (link.kind == RemoteStorageKind.ICLOUD_SHARE && link.lastSyncedAt != null) {
                // The owner stopped sharing (or removed this participant). Keep the local copy and drop the dead link.
                val disconnected = saveLink(boardSetId, null)
                bumpVersion()
                return@guarded RemoteSyncOutcome.Disconnected(disconnected)
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
        private const val MAX_IGNORED_LOCATIONS = 500
        const val DEFAULT_AUTO_PUSH_DEBOUNCE_MS = 1_500L
        const val DEFAULT_AUTO_SYNC_THROTTLE_MS = 15_000L
        const val DEFAULT_AUTO_SYNC_INTERVAL_MS = 45_000L
    }
}
