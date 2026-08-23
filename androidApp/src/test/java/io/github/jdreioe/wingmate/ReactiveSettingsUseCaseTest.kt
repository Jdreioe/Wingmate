package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.infrastructure.InMemoryConfigRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveSettingsUseCaseTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun updatingSettingsThroughUseCaseNotifiesRunningApp() = runTest(dispatcher) {
        val repository = InMemorySettingsRepository()
        val stateManager = SettingsStateManager(repository, InMemoryConfigRepository())
        val useCase = SettingsUseCase(repository, stateManager)
        advanceUntilIdle()

        useCase.update(repository.get().copy(primaryLanguage = "da-DK"))

        assertEquals("da-DK", stateManager.getCurrentSettings().primaryLanguage)
    }
}
