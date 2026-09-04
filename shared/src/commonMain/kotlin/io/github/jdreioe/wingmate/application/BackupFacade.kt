package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.CancellationException

enum class BackupOperationStatus {
    Success,
    Unavailable,
    NotFound,
    ValidationFailure,
    PersistenceFailure,
    NetworkFailure,
    ShareFailure,
}

data class BackupOperationResult(
    val status: BackupOperationStatus,
    val message: String? = null,
    val isRetryable: Boolean = false,
) {
    val isSuccess: Boolean get() = status == BackupOperationStatus.Success
}

data class BackupExportResult(
    val status: BackupOperationStatus,
    val content: ByteArray? = null,
    val message: String? = null,
    val isRetryable: Boolean = false,
) {
    val isSuccess: Boolean get() = status == BackupOperationStatus.Success

    fun withoutContent(): BackupOperationResult = BackupOperationResult(
        status = status,
        message = message,
        isRetryable = isRetryable,
    )
}

/** A feature-scoped native boundary around backup creation and restoration. */
class BackupFacade(
    private val backupManager: BackupManager,
) {
    suspend fun exportBackup(): BackupExportResult = try {
        BackupExportResult(
            status = BackupOperationStatus.Success,
            content = backupManager.exportBackup(),
        )
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: BackupMediaNotFoundException) {
        BackupExportResult(
            status = BackupOperationStatus.NotFound,
            message = "A referenced media file could not be found",
            isRetryable = false,
        )
    } catch (_: Exception) {
        BackupExportResult(
            status = BackupOperationStatus.PersistenceFailure,
            message = "Backup could not be created",
            isRetryable = true,
        )
    }

    suspend fun restoreBackup(path: String): BackupOperationResult = try {
        when (val result = backupManager.restoreBackup(path)) {
            is BackupRestoreResult.Success -> BackupOperationResult(BackupOperationStatus.Success)
            is BackupRestoreResult.Failure -> BackupOperationResult(
                status = result.kind.toOperationStatus(),
                message = result.message,
                isRetryable = result.isRetryable,
            )
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        BackupOperationResult(
            status = BackupOperationStatus.PersistenceFailure,
            message = "Backup could not be restored safely",
            isRetryable = true,
        )
    }
}

/** Adds platform sharing to the backup feature without coupling it to a native client. */
class BackupSharingFacade(
    private val backupFacade: BackupFacade,
    private val shareService: ShareService,
) {
    suspend fun shareBackup(): BackupOperationResult {
        val export = backupFacade.exportBackup()
        val content = export.content ?: return export.withoutContent()
        return try {
            if (shareService.shareFile(BACKUP_FILE_NAME, content)) {
                BackupOperationResult(BackupOperationStatus.Success)
            } else {
                BackupOperationResult(
                    status = BackupOperationStatus.ShareFailure,
                    message = "Backup could not be shared",
                    isRetryable = true,
                )
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            BackupOperationResult(
                status = BackupOperationStatus.ShareFailure,
                message = "Backup could not be shared",
                isRetryable = true,
            )
        }
    }

    suspend fun restoreBackup(path: String): BackupOperationResult = backupFacade.restoreBackup(path)

    private companion object {
        const val BACKUP_FILE_NAME = "wingmate-backup.wingmate-backup"
    }
}

private fun BackupFailureKind.toOperationStatus(): BackupOperationStatus = when (this) {
    BackupFailureKind.Unavailable -> BackupOperationStatus.Unavailable
    BackupFailureKind.NotFound -> BackupOperationStatus.NotFound
    BackupFailureKind.Validation -> BackupOperationStatus.ValidationFailure
    BackupFailureKind.Persistence -> BackupOperationStatus.PersistenceFailure
    BackupFailureKind.Network -> BackupOperationStatus.NetworkFailure
}
