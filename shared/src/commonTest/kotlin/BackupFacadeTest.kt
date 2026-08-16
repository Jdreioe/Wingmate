import io.github.jdreioe.wingmate.application.BackupFacade
import io.github.jdreioe.wingmate.application.BackupFailureKind
import io.github.jdreioe.wingmate.application.BackupManager
import io.github.jdreioe.wingmate.application.BackupOperationStatus
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.application.BackupSharingFacade
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupFacadeTest {
    @Test
    fun exportReturnsContentWithoutExposingManagerExceptions() = runBlocking {
        val content = "backup".encodeToByteArray()
        val result = BackupFacade(FakeBackupManager(exported = content)).exportBackup()

        assertEquals(BackupOperationStatus.Success, result.status)
        assertContentEquals(content, result.content)
        assertTrue(result.isSuccess)
    }

    @Test
    fun restoreKeepsFailureTypeAndRetryability() = runBlocking {
        val facade = BackupFacade(
            FakeBackupManager(
                restored = BackupRestoreResult.Failure(
                    kind = BackupFailureKind.Validation,
                    message = "Invalid backup",
                    isRetryable = false,
                ),
            ),
        )

        val result = facade.restoreBackup("backup.wingmate-backup")

        assertEquals(BackupOperationStatus.ValidationFailure, result.status)
        assertEquals("Invalid backup", result.message)
        assertFalse(result.isRetryable)
    }

    @Test
    fun cancellationIsNotConvertedIntoAResult() = runBlocking {
        val facade = BackupFacade(
            object : BackupManager {
                override suspend fun exportBackup(): ByteArray = throw CancellationException("cancelled")
                override suspend fun restoreBackup(path: String): BackupRestoreResult =
                    throw CancellationException("cancelled")
            },
        )

        assertFailsWith<CancellationException> { facade.exportBackup() }
        assertFailsWith<CancellationException> { facade.restoreBackup("backup.wingmate-backup") }
        Unit
    }

    @Test
    fun sharingFailureIsTypedAndRetryable() = runBlocking {
        val sharing = BackupSharingFacade(
            backupFacade = BackupFacade(FakeBackupManager(exported = byteArrayOf(1, 2, 3))),
            shareService = FakeShareService(shared = false),
        )

        val result = sharing.shareBackup()

        assertEquals(BackupOperationStatus.ShareFailure, result.status)
        assertTrue(result.isRetryable)
    }

    private class FakeBackupManager(
        private val exported: ByteArray = byteArrayOf(),
        private val restored: BackupRestoreResult = BackupRestoreResult.Failure(
            kind = BackupFailureKind.NotFound,
            message = "Not found",
            isRetryable = false,
        ),
    ) : BackupManager {
        override suspend fun exportBackup(): ByteArray = exported
        override suspend fun restoreBackup(path: String): BackupRestoreResult = restored
    }

    private class FakeShareService(
        private val shared: Boolean,
    ) : ShareService {
        override fun shareAudio(filePath: String): Boolean = false
        override fun shareText(text: String): Boolean = false
        override fun shareFile(fileName: String, content: ByteArray): Boolean = shared
    }
}
