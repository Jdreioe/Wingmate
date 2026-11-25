package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.CloudShareRecord
import io.github.jdreioe.wingmate.domain.obf.CloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume

/** Result of a CloudKit fetch, created by the Swift bridge. A non-null [errorCode] is a [RemoteStorageErrorCode] name. */
class IosCloudKitRecordResult(
    val found: Boolean,
    val revision: String?,
    val packageData: NSData?,
    val errorCode: String?,
    val errorMessage: String?,
)

/** Result of a CloudKit save or release, created by the Swift bridge. */
class IosCloudKitWriteResult(
    val revision: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

/**
 * CloudKit lives in the Swift app (it also owns share acceptance and `UICloudSharingController`),
 * so the Swift side implements this bridge and registers it with [IosCloudKitScreenShareStorage.register].
 */
interface IosCloudKitScreenBridge {
    fun fetchScreenRecord(location: String, includePackage: Boolean, completion: (IosCloudKitRecordResult) -> Unit)
    fun saveScreenRecord(
        location: String,
        packageData: NSData,
        expectedRevision: String?,
        completion: (IosCloudKitWriteResult) -> Unit,
    )
    fun releaseScreenRecord(location: String, completion: (IosCloudKitWriteResult) -> Unit)
}

/** [CloudShareScreenStorage] backed by the Swift CloudKit bridge (CKShare, iOS → iOS). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosCloudKitScreenShareStorage : CloudShareScreenStorage {
    override val isSupported: Boolean get() = bridgeRef.value != null

    override suspend fun fetch(location: String, includePackage: Boolean): CloudShareRecord? {
        val result = suspendCancellableCoroutine { continuation ->
            requireBridge().fetchScreenRecord(location, includePackage) { continuation.resume(it) }
        }
        result.errorCode?.let { throw remoteError(it, result.errorMessage) }
        if (!result.found) return null
        return CloudShareRecord(revision = result.revision, bytes = result.packageData?.toByteArray())
    }

    override suspend fun save(location: String, bytes: ByteArray, expectedRevision: String?): String {
        val data = bytes.toNSData()
        val result = suspendCancellableCoroutine { continuation ->
            requireBridge().saveScreenRecord(location, data, expectedRevision) { continuation.resume(it) }
        }
        result.errorCode?.let { throw remoteError(it, result.errorMessage) }
        return result.revision
            ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "iCloud did not return a record revision")
    }

    override suspend fun release(location: String) {
        val result = suspendCancellableCoroutine { continuation ->
            requireBridge().releaseScreenRecord(location) { continuation.resume(it) }
        }
        result.errorCode?.let { throw remoteError(it, result.errorMessage) }
    }

    private fun requireBridge(): IosCloudKitScreenBridge = bridgeRef.value
        ?: throw RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "iCloud sharing is not available")

    private fun remoteError(code: String, message: String?) = RemoteStorageException(
        RemoteStorageErrorCode.entries.firstOrNull { it.name == code } ?: RemoteStorageErrorCode.IO_ERROR,
        message?.takeIf(String::isNotBlank) ?: "iCloud request failed",
    )

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }

    private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
    }

    companion object {
        private val bridgeRef = AtomicReference<IosCloudKitScreenBridge?>(null)

        fun register(bridge: IosCloudKitScreenBridge) {
            bridgeRef.value = bridge
        }
    }
}
