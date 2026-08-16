package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class IosPreferencesJsonStoreTest {
    private val suiteName = "wingmate.persistence.${NSUUID.UUID().UUIDString}"
    private val defaults = assertNotNull(NSUserDefaults(suiteName = suiteName))
    private val serializer = ListSerializer(String.serializer())
    private val json = Json

    @AfterTest
    fun cleanUp() {
        defaults.removePersistentDomainForName(suiteName)
    }

    @Test
    fun corruptPayloadIsQuarantinedAndNotOverwritten() = runBlocking {
        defaults.setObject("[\"truncated", forKey = "items")
        val store = store()

        val failure = assertFailsWith<PersistenceException> {
            store.update(::emptyList) { it + "new" }
        }

        assertEquals(PersistenceError.CorruptOrUnsupported, failure.error)
        assertEquals("[\"truncated", defaults.stringForKey("items"))
        assertEquals("[\"truncated", defaults.stringForKey("items_corrupt_backup"))
    }

    @Test
    fun failedNativeWriteRestoresPreviousPayload() = runBlocking {
        defaults.setObject("[\"existing\"]", forKey = "items")
        val store = store(synchronize = { false })

        val failure = assertFailsWith<PersistenceException> {
            store.update(::emptyList) { it + "new" }
        }

        assertEquals(PersistenceError.Io, failure.error)
        assertEquals("[\"existing\"]", defaults.stringForKey("items"))
    }

    @Test
    fun overlappingMutationsAreLinearized() = runBlocking {
        val store = store()

        coroutineScope {
            (0 until 40).map { index ->
                async { store.update(::emptyList) { it + index.toString() } }
            }.awaitAll()
        }

        assertEquals(40, store.read(::emptyList).toSet().size)
    }

    private fun store(synchronize: () -> Boolean = { defaults.synchronize() }) =
        IosPreferencesJsonStore(
            key = "items",
            encode = { json.encodeToString(serializer, it) },
            decode = { json.decodeFromString(serializer, it) },
            defaults = defaults,
            synchronize = synchronize,
        )
}
