package io.github.jdreioe.wingmate.tools

import io.github.jdreioe.wingmate.infrastructure.DesktopSqlConfigRepository
import kotlinx.coroutines.runBlocking
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig

fun main() {
    val repo = DesktopSqlConfigRepository()
    runBlocking {
        repo.saveSpeechConfig(SpeechServiceConfig(endpoint = "test-region", subscriptionKey = "abc-123"))
        println("wrote config")
    }
}
