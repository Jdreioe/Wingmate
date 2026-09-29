import io.github.jdreioe.wingmate.application.BackupFacade
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.createCoreDataModule
import io.github.jdreioe.wingmate.di.appModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
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
}
