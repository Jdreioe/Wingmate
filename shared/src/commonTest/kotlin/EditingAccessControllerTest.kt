import io.github.jdreioe.wingmate.application.DefaultEditingAccessStore
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.InMemorySecureEditingCredentialStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditingAccessControllerTest {
    @Test
    fun configuresVerifiesLocksAndTimesOut() = runBlocking {
        var now = 1_000L
        val store = DefaultEditingAccessStore(InMemorySecureEditingCredentialStorage(), iterations = 2)
        val controller = EditingAccessController(store, timeoutMillis = 100, nowMillis = { now })

        assertFalse(controller.requiresUnlock())
        controller.configure("1234")
        assertFalse(controller.requiresUnlock())
        controller.lock()
        assertTrue(controller.requiresUnlock())
        assertFalse(controller.unlock("9999"))
        assertTrue(controller.unlock("1234"))
        now += 101
        assertTrue(controller.requiresUnlock())
    }

    @Test
    fun disablingRequiresTheCurrentCode() = runBlocking {
        val store = DefaultEditingAccessStore(InMemorySecureEditingCredentialStorage(), iterations = 2)
        val controller = EditingAccessController(store)
        controller.configure("1234")
        assertFalse(controller.disable("0000"))
        assertTrue(controller.disable("1234"))
        assertFalse(controller.requiresUnlock())
    }

    @Test
    fun repeatedWrongCodesDelayTheNextAttempt() = runBlocking {
        var now = 1_000L
        val store = DefaultEditingAccessStore(InMemorySecureEditingCredentialStorage(), iterations = 2)
        val controller = EditingAccessController(store, nowMillis = { now })
        controller.configure("1234")
        controller.lock()
        repeat(4) { assertFalse(controller.unlock("0000")) }
        assertEquals(0, controller.state.value.retryNotBeforeMillis)
        assertFalse(controller.unlock("0000"))
        val waitUntil = controller.state.value.retryNotBeforeMillis
        assertTrue(waitUntil > now)
        assertFalse(controller.unlock("1234"))
        now = waitUntil
        assertTrue(controller.unlock("1234"))
        assertEquals(0, controller.state.value.failedAttempts)
    }
}
