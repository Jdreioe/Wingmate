import io.github.jdreioe.wingmate.application.BackupFacade
import io.github.jdreioe.wingmate.application.BackupManager
import io.github.jdreioe.wingmate.application.BackupSharingFacade
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.CategoryUseCase
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.CommunicationSessionFacade
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.PredictionFacade
import io.github.jdreioe.wingmate.application.PronunciationFacade
import io.github.jdreioe.wingmate.application.RemoteScreenActions
import io.github.jdreioe.wingmate.application.RemoteScreenSyncUseCase
import io.github.jdreioe.wingmate.application.SettingsFacade
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.application.SymbolSearchFacade
import io.github.jdreioe.wingmate.application.TypingScreenFacade
import io.github.jdreioe.wingmate.application.TypingScreenUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.di.sharedModules
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.BoardSpeechCache
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechAudioRouter
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.UserDataManager
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Source
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class KoinWiringTest {
    // typingModule starts PhraseListStore, whose executor runs on Dispatchers.Main.
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun startWithPlatformStubs(): KoinApplication = koinApplication {
        modules(sharedModules() + platformStubsModule())
    }

    @Test
    fun allPublicFacadesResolveFromSharedModules() {
        val application = startWithPlatformStubs()
        try {
            val koin = application.koin
            assertNotNull(koin.get<BackupFacade>())
            assertNotNull(koin.get<BackupSharingFacade>())
            assertNotNull(koin.get<SpeechFacade>())
            assertNotNull(koin.get<SettingsFacade>())
            assertNotNull(koin.get<BoardsFacade>())
            assertNotNull(koin.get<CommunicationFacade>())
            assertNotNull(koin.get<CommunicationSessionFacade>())
            assertNotNull(koin.get<TypingScreenFacade>())
            assertNotNull(koin.get<PredictionFacade>())
            assertNotNull(koin.get<PronunciationFacade>())
            assertNotNull(koin.get<SymbolSearchFacade>())
        } finally {
            application.close()
        }
    }

    @Test
    fun allCoreUseCasesAndServicesResolveFromSharedModules() {
        val application = startWithPlatformStubs()
        try {
            val koin = application.koin
            assertNotNull(koin.get<SettingsStateManager>())
            assertNotNull(koin.get<SettingsUseCase>())
            assertNotNull(koin.get<UserDataManager>())
            assertNotNull(koin.get<EditingAccessController>())
            assertNotNull(koin.get<VoiceUseCase>())
            assertNotNull(koin.get<PhraseUseCase>())
            assertNotNull(koin.get<CategoryUseCase>())
            assertNotNull(koin.get<AddPhraseUseCase>())
            assertNotNull(koin.get<GetPhrasesAndCategoriesUseCase>())
            assertNotNull(koin.get<DeletePhraseUseCase>())
            assertNotNull(koin.get<UpdatePhraseUseCase>())
            assertNotNull(koin.get<PhraseListStore>())
            assertNotNull(koin.get<TypingScreenUseCase>())
            assertNotNull(koin.get<TextPredictionService>())
            assertNotNull(koin.get<CommunicationSession>())
            assertNotNull(koin.get<SpeechAudioRouter>())
            assertNotNull(koin.get<BoardSetUseCase>())
            assertNotNull(koin.get<RemoteScreenSyncUseCase>())
            assertNotNull(koin.get<RemoteScreenActions>())
            assertNotNull(koin.get<BoardSpeechCache>())
            assertNotNull(koin.get<BoardSetSpeechCacheUseCase>())
            assertNotNull(koin.get<BoardImportService>())
            assertNotNull(koin.get<CompleteBackupManager>())
            assertNotNull(koin.get<BackupManager>())
            assertNotNull(koin.get<AacLogger>())
        } finally {
            application.close()
        }
    }

    @Test
    fun sharedSingletonsPreserveExpectedIdentity() {
        val application = startWithPlatformStubs()
        try {
            val koin = application.koin
            assertSame(
                koin.get<PhraseListStore>(),
                koin.get<CommunicationFacade>().phraseListStore(),
                "CommunicationFacade must expose the process-wide PhraseListStore singleton",
            )
            assertSame(
                koin.get<CompleteBackupManager>(),
                koin.get<BackupManager>(),
                "BackupManager must alias the CompleteBackupManager singleton",
            )
        } finally {
            application.close()
        }
    }

    @Test
    fun settingsUseCaseUpdatesSharedSettingsStateManager() = runTest {
        val application = startWithPlatformStubs()
        try {
            val koin = application.koin
            val useCase = koin.get<SettingsUseCase>()
            val stateManager = koin.get<SettingsStateManager>()

            val updated = Settings(primaryLanguage = "da-DK")
            useCase.update(updated)

            assertEquals("da-DK", stateManager.getCurrentSettings().primaryLanguage)
        } finally {
            application.close()
        }
    }

    private fun platformStubsModule() = module {
        single<ShareService> {
            object : ShareService {
                override fun shareAudio(filePath: String): Boolean = false
                override fun shareText(text: String): Boolean = false
                override fun shareFile(fileName: String, content: ByteArray): Boolean = false
            }
        }
        single<FilePicker> {
            object : FilePicker {
                override suspend fun pickFile(title: String, extensions: List<String>): String? = null
                override suspend fun readFileAsText(path: String): String? = null
                override suspend fun openArchive(path: String): ArchiveReader? = null
            }
        }
        single<FileSystem> { NoopFileSystem() }
    }

    private class NoopFileSystem : FileSystem() {
        override fun canonicalize(path: Path): Path = path
        override fun metadataOrNull(path: Path): FileMetadata? = null
        override fun list(dir: Path): List<Path> = emptyList()
        override fun listOrNull(dir: Path): List<Path>? = null
        override fun openReadOnly(file: Path): FileHandle = error("unused in wiring test")
        override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle = error("unused in wiring test")
        override fun source(file: Path): Source = error("unused in wiring test")
        override fun sink(file: Path, mustCreate: Boolean): Sink = error("unused in wiring test")
        override fun appendingSink(file: Path, mustExist: Boolean): Sink = error("unused in wiring test")
        override fun createDirectory(dir: Path, mustCreate: Boolean) = Unit
        override fun atomicMove(source: Path, target: Path) = Unit
        override fun delete(path: Path, mustExist: Boolean) = Unit
        override fun createSymlink(source: Path, target: Path) = Unit
    }
}
