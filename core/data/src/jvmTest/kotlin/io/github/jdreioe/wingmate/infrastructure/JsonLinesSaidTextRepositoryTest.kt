package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import io.github.jdreioe.wingmate.domain.SaidText
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonLinesSaidTextRepositoryTest {
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "said-text-${Random.nextLong()}"
    private val file = root / "history" / "said_text.jsonl"

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)

    private fun repository() = JsonLinesSaidTextRepository(FileSystem.SYSTEM, file)

    @Test
    fun addedEntriesAreNumberedAndSurviveReopening() = runBlocking {
        repository().apply {
            add(SaidText(saidText = "Hello", date = 1L, createdAt = 1L))
            add(SaidText(saidText = "Thanks", date = 2L, createdAt = 2L))
        }

        val reopened = repository().list()
        assertEquals(listOf("Hello", "Thanks"), reopened.map { it.saidText })
        assertEquals(listOf(1, 2), reopened.map { it.position })
        assertEquals(2, FileSystem.SYSTEM.read(file) { readUtf8() }.lines().count { it.isNotBlank() })
    }

    @Test
    fun anAppendCutShortIsDroppedAndTheNextWriteRepairsTheFile() = runBlocking {
        repository().add(SaidText(saidText = "Hello", date = 1L, createdAt = 1L))
        FileSystem.SYSTEM.appendingSink(file).use { sink ->
            okio.Buffer().writeUtf8("{\"saidText\":\"Tha").let { sink.write(it, it.size) }
        }

        val repository = repository()
        assertEquals(listOf("Hello"), repository.list().map { it.saidText })
        repository.add(SaidText(saidText = "Thanks", date = 2L, createdAt = 2L))

        assertEquals(listOf("Hello", "Thanks"), repository().list().map { it.saidText })
    }

    @Test
    fun anUnreadableEarlierLineFailsInsteadOfLosingHistory() = runBlocking {
        FileSystem.SYSTEM.createDirectories(file.parent!!)
        FileSystem.SYSTEM.write(file) { writeUtf8("not json\n{\"saidText\":\"Hello\"}\n") }

        val failure = assertFailsWith<PersistenceException> { repository().list() }
        assertEquals(PersistenceError.CorruptOrUnsupported, failure.error)
    }

    @Test
    fun replaceAllAndDeleteAllRewriteTheHistory() = runBlocking {
        val repository = repository()
        repository.add(SaidText(saidText = "Old", date = 1L, createdAt = 1L))

        repository.replaceAll(listOf(SaidText(id = 7, saidText = "Restored", position = 7)))
        assertEquals(listOf("Restored"), repository().list().map { it.saidText })

        repository.deleteAll()
        assertEquals(emptyList(), repository().list())
    }
}
