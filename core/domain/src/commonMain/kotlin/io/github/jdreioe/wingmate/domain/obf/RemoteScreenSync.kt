package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OBZ manifest extension carrying the Screen name, so renames survive remote round trips. */
const val OBF_SCREEN_NAME_EXTENSION = "ext_wingmate_screen_name"

/**
 * Bring-Your-Own-Storage (BYOS) backends for cross-platform remote Screen editing.
 * None of these require Wingmate-hosted servers or developer-managed accounts.
 */
@Serializable
enum class RemoteStorageKind(val wireValue: String) {
    @SerialName("webdav")
    WEBDAV("webdav"),

    @SerialName("github")
    GITHUB("github"),

    @SerialName("https_url")
    HTTPS_URL("https_url"),

    @SerialName("linked_file")
    LINKED_FILE("linked_file"),

    /** Apple CloudKit share (iOS → iOS). Data lives in the Screen owner's iCloud account. */
    @SerialName("icloud_share")
    ICLOUD_SHARE("icloud_share"),

    /** Google Drive file reached through the Drive API (`drive.file` scope). */
    @SerialName("google_drive")
    GOOGLE_DRIVE("google_drive"),

    /** OneDrive file reached through Microsoft Graph. */
    @SerialName("onedrive")
    ONEDRIVE("onedrive");

    /** True for storage reached through a signed-in cloud drive account (OAuth). */
    val isCloudDrive: Boolean
        get() = this == GOOGLE_DRIVE || this == ONEDRIVE

    companion object {
        fun fromWireValue(value: String?): RemoteStorageKind? =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) || it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Non-secret remote link metadata stored on a Screen ([ObfBoardSet]).
 *
 * Passwords, app tokens, and personal access tokens must NEVER be stored here;
 * they belong exclusively in [RemoteScreenCredentialStorage] keyed by [credentialId].
 */
@Serializable
data class RemoteScreenLink(
    val kind: RemoteStorageKind,
    /**
     * Provider-specific non-secret location:
     * - [RemoteStorageKind.WEBDAV]: HTTPS WebDAV file URL (e.g. `https://cloud.example/remote.php/dav/files/user/screen.obz`)
     * - [RemoteStorageKind.GITHUB]: `owner/repo`
     * - [RemoteStorageKind.HTTPS_URL]: HTTPS URL to an `.obz` Vocabulary package (read-only)
     * - [RemoteStorageKind.LINKED_FILE]: platform file handle (SAF URI, iOS bookmark, Linux path)
     * - [RemoteStorageKind.ICLOUD_SHARE]: CloudKit record address
     *   (`icloud://private/<zone>/<record>` for the owner, `icloud://shared/<owner>/<zone>/<record>` for participants)
     * - [RemoteStorageKind.GOOGLE_DRIVE]: Drive file ID
     * - [RemoteStorageKind.ONEDRIVE]: `<driveId>/<itemId>`
     */
    val location: String,
    val displayName: String = "",
    /** GitHub branch name (defaults to `main` when blank). */
    val branch: String? = null,
    /** GitHub repository file path (e.g. `screens/home.obz`). */
    val filePath: String? = null,
    /** Optional non-secret username for WebDAV Basic authentication. */
    val username: String? = null,
    /** When true, Wingmate only pulls remote changes and never pushes local changes. */
    val readOnly: Boolean = false,
    /** Opaque key into [RemoteScreenCredentialStorage]. Contains no secret material. */
    val credentialId: String? = null,
    /** Last observed remote revision (ETag, GitHub blob SHA, or `sha256:<hex>`). */
    val lastSyncedRemoteRevision: String? = null,
    /** SHA-256 hex digest of the remote `.obz` bytes at the last sync point. */
    val lastSyncedRemoteHash: String? = null,
    /**
     * Device-local fingerprint (SHA-256 of the canonical Screen graph) at the last sync point.
     * Used to detect local edits without re-exporting media on every check.
     */
    val lastSyncedLocalHash: String? = null,
    /** Epoch milliseconds of the last completed sync. */
    val lastSyncedAt: Long? = null,
    /** True when both local and remote changed since the last sync and need resolution. */
    val hasConflict: Boolean = false,
) {
    val isWritable: Boolean
        get() = !readOnly && kind != RemoteStorageKind.HTTPS_URL

    fun resolvedDisplayName(): String = displayName.trim().ifEmpty {
        when (kind) {
            RemoteStorageKind.GITHUB -> listOfNotNull(
                location.trim().takeIf(String::isNotEmpty),
                filePath?.trim()?.takeIf(String::isNotEmpty),
            ).joinToString(" / ")
            RemoteStorageKind.WEBDAV,
            RemoteStorageKind.HTTPS_URL,
            RemoteStorageKind.LINKED_FILE,
            -> location.substringAfterLast('/').ifBlank { location }
            RemoteStorageKind.ICLOUD_SHARE -> "iCloud"
            RemoteStorageKind.GOOGLE_DRIVE -> "Google Drive"
            RemoteStorageKind.ONEDRIVE -> "OneDrive"
        }
    }
}

enum class RemoteStorageErrorCode {
    INVALID_CONFIGURATION,
    INSECURE_URL,
    UNAUTHORIZED,
    NOT_FOUND,
    CONFLICT,
    READ_ONLY,
    NETWORK_UNAVAILABLE,
    UNSUPPORTED_PLATFORM,
    IO_ERROR,
}

class RemoteStorageException(
    val code: RemoteStorageErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class RemoteFileMetadata(
    val revision: String?,
    val exists: Boolean = true,
)

data class RemoteFileContent(
    val bytes: ByteArray,
    val revision: String?,
    val contentHash: String,
)

data class RemoteWriteResult(
    val revision: String?,
    val contentHash: String,
)

/** Storage transport abstraction for reading and writing remote `.obz` Screen packages. */
interface RemoteScreenStorage {
    suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata
    suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent
    suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String = "Update Wingmate Screen",
    ): RemoteWriteResult
}

/**
 * Device-local secure store for remote Screen credentials (WebDAV password/token, GitHub PAT).
 * Values stored here are never exported in `.obz` packages or `.wingmate-backup` archives.
 */
interface RemoteScreenCredentialStorage {
    val isSupported: Boolean
    suspend fun read(credentialId: String): String?
    suspend fun write(credentialId: String, secret: String)
    suspend fun delete(credentialId: String)
}

/** No-op fallback used in tests or headless environments without a keyring. */
object UnsupportedRemoteScreenCredentialStorage : RemoteScreenCredentialStorage {
    override val isSupported: Boolean = false
    override suspend fun read(credentialId: String): String? = null
    override suspend fun write(credentialId: String, secret: String) {
        throw RemoteStorageException(
            RemoteStorageErrorCode.UNSUPPORTED_PLATFORM,
            "Secure credential storage is unavailable on this device",
        )
    }
    override suspend fun delete(credentialId: String) = Unit
}

/** Handle to an OS-managed or cloud-synced `.obz` file (SAF URI, iOS bookmark, Linux path). */
data class LinkedFileHandle(
    val location: String,
    val displayName: String,
    val writable: Boolean = true,
)

/**
 * Platform adapter for Draw.io-style linked `.obz` files stored in iCloud Drive, Google Drive,
 * OneDrive, Dropbox, Syncthing, or a local synced folder.
 */
interface LinkedRemoteFileStorage {
    val isSupported: Boolean
    suspend fun pickExistingFile(): LinkedFileHandle?
    suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle?
    suspend fun readBytes(location: String): ByteArray
    suspend fun writeBytes(location: String, bytes: ByteArray)
    suspend fun lastModifiedMillis(location: String): Long? = null
    suspend fun releaseAccess(location: String) = Unit
}

object UnsupportedLinkedRemoteFileStorage : LinkedRemoteFileStorage {
    override val isSupported: Boolean = false
    override suspend fun pickExistingFile(): LinkedFileHandle? = null
    override suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle? = null
    override suspend fun readBytes(location: String): ByteArray =
        throw RemoteStorageException(
            RemoteStorageErrorCode.UNSUPPORTED_PLATFORM,
            "Linked files are not supported on this platform",
        )
    override suspend fun writeBytes(location: String, bytes: ByteArray) {
        throw RemoteStorageException(
            RemoteStorageErrorCode.UNSUPPORTED_PLATFORM,
            "Linked files are not supported on this platform",
        )
    }
}

/** One CloudKit Screen record as seen by this device. */
class CloudShareRecord(
    /** CloudKit `recordChangeTag`; null while the shared record has no package yet. */
    val revision: String?,
    /** The `.obz` package bytes, or null when the record has no package or it was not requested. */
    val bytes: ByteArray?,
)

/**
 * Platform adapter for Screens shared through Apple CloudKit (CKShare). The Screen owner keeps
 * the record in their private iCloud database and invites participants; data never touches
 * Wingmate-run servers and storage counts against the owner's own iCloud quota.
 */
interface CloudShareScreenStorage {
    val isSupported: Boolean

    /** Fetches the record; returns null when it no longer exists or was unshared. */
    suspend fun fetch(location: String, includePackage: Boolean): CloudShareRecord?

    /**
     * Saves [bytes] only when the server record still has [expectedRevision] (null = no package yet).
     * Throws [RemoteStorageErrorCode.CONFLICT] otherwise. Returns the new revision.
     */
    suspend fun save(location: String, bytes: ByteArray, expectedRevision: String?): String

    /** Owner: stops sharing and deletes the record. Participant: leaves the share. */
    suspend fun release(location: String) = Unit
}

object UnsupportedCloudShareScreenStorage : CloudShareScreenStorage {
    override val isSupported: Boolean = false
    override suspend fun fetch(location: String, includePackage: Boolean): CloudShareRecord? = unsupported()
    override suspend fun save(location: String, bytes: ByteArray, expectedRevision: String?): String = unsupported()

    private fun unsupported(): Nothing = throw RemoteStorageException(
        RemoteStorageErrorCode.UNSUPPORTED_PLATFORM,
        "iCloud shared Screens are only available on iPhone and iPad",
    )
}

/** A Screen file the signed-in cloud drive account can open. */
@Serializable
data class RemoteFileChoice(
    val location: String,
    val name: String,
)

/**
 * Interactive sign-in for cloud drives (Google Drive, OneDrive). Implementations return an opaque
 * token bundle that Wingmate stores only in [RemoteScreenCredentialStorage]; it never reaches the UI,
 * OBZ exports, or backups.
 */
interface CloudDriveSignIn {
    fun isSupported(kind: RemoteStorageKind): Boolean

    /** Runs the provider's sign-in. Returns the token bundle, or null when the person cancelled. */
    suspend fun signIn(kind: RemoteStorageKind): String?

    /** Gets a new bundle for platform-managed accounts that have no refresh token (Android Google). */
    suspend fun refreshPlatformToken(kind: RemoteStorageKind): String? = null
}

object UnsupportedCloudDriveSignIn : CloudDriveSignIn {
    override fun isSupported(kind: RemoteStorageKind): Boolean = false
    override suspend fun signIn(kind: RemoteStorageKind): String? = throw RemoteStorageException(
        RemoteStorageErrorCode.UNSUPPORTED_PLATFORM,
        "Sign-in for this storage is not set up in this build of Wingmate",
    )
}

/** Turns a stored token bundle into a currently valid access token, refreshing and persisting it as needed. */
interface CloudDriveAccessTokens {
    suspend fun accessToken(link: RemoteScreenLink, secret: String?, forceRefresh: Boolean = false): String
}

/** Cloud drive file operations used before a Screen is linked. */
interface CloudDriveFiles {
    /** `.obz` files the account lets Wingmate open (for Google, files Wingmate created or was given). */
    suspend fun listScreenFiles(kind: RemoteStorageKind, secret: String): List<RemoteFileChoice>

    /** Creates an empty `.obz` file; the first sync uploads the Screen into it. */
    suspend fun createScreenFile(kind: RemoteStorageKind, secret: String, fileName: String): RemoteFileChoice

    /** Human-readable account name (email or display name), or null. */
    suspend fun accountLabel(kind: RemoteStorageKind, secret: String): String? = null
}

enum class RemoteConflictResolution(val wireValue: String) {
    USE_REMOTE("use_remote"),
    KEEP_LOCAL("keep_local"),
    SAVE_REMOTE_AS_COPY("save_remote_as_copy");

    companion object {
        fun fromWireValue(value: String?): RemoteConflictResolution? =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) || it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

sealed interface RemoteSyncOutcome {
    data class UpToDate(val boardSet: ObfBoardSet) : RemoteSyncOutcome
    data class Pulled(val boardSet: ObfBoardSet, val warningsCount: Int = 0) : RemoteSyncOutcome
    data class Pushed(val boardSet: ObfBoardSet) : RemoteSyncOutcome
    data class Conflict(val boardSet: ObfBoardSet, val message: String) : RemoteSyncOutcome
    /** The remote copy changed, but this Screen is frozen by a Screen lock and was not updated. */
    data class LockedUpdateAvailable(val boardSet: ObfBoardSet) : RemoteSyncOutcome
    data class CopiedRemote(val localBoardSet: ObfBoardSet, val remoteCopyBoardSet: ObfBoardSet) : RemoteSyncOutcome
    data class Disconnected(val boardSet: ObfBoardSet) : RemoteSyncOutcome
    data class ReadOnlyLocalEdits(val boardSet: ObfBoardSet) : RemoteSyncOutcome
    data class Offline(val boardSetId: String?, val message: String) : RemoteSyncOutcome
    data class Error(
        val boardSetId: String?,
        val code: RemoteStorageErrorCode,
        val message: String,
    ) : RemoteSyncOutcome
}
