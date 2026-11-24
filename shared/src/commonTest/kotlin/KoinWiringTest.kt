import io.github.jdreioe.wingmate.application.BackupFacade
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.createCoreDataModule
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class KoinWiringTest {
    // appModule starts PhraseListStore, whose executor runs on Dispatchers.Main.
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun editingAccessDependenciesUseTheirDefaultConfiguration() {
        val application = koinApplication {
            modules(createCoreDataModule())
        }
        try {
            assertNotNull(application.koin.get<EditingAccessController>())
        } finally {
            application.close()
        }
    }

    @Test
    fun backupFacadeIsCreatedFromItsExplicitDependencies() {
        val application = koinApplication {
            modules(createCoreDataModule(), appModule)
        }
        try {
            assertNotNull(application.koin.get<BackupFacade>())
        } finally {
            application.close()
        }
    }

    @Test
    fun speechFacadeIsCreatedFromItsExplicitDependencies() {
        val application = koinApplication {
            modules(createCoreDataModule(), appModule)
        }
        try {
            assertNotNull(application.koin.get<SpeechFacade>())
        } finally {
            application.close()
        }
    }

    @Test
    fun boardsFacadeIsCreatedFromItsExplicitDependencies() {
        val platformStub = module {
            single<ShareService> {
                object : ShareService {
                    override fun shareAudio(filePath: String): Boolean = false
                    override fun shareText(text: String): Boolean = false
                    override fun shareFile(fileName: String, content: ByteArray): Boolean = false
                }
            }
        }
        val application = koinApplication {
            modules(createCoreDataModule(), appModule, platformStub)
        }
        try {
            assertNotNull(application.koin.get<BoardsFacade>())
        } finally {
            application.close()
        }
    }
}
