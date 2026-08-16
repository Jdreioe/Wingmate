import io.github.jdreioe.wingmate.application.BackupFacade
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.createCoreDataModule
import io.github.jdreioe.wingmate.di.appModule
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

class KoinWiringTest {
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
}
