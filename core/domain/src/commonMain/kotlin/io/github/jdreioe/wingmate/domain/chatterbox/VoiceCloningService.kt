package io.github.jdreioe.wingmate.domain.chatterbox

interface VoiceCloningService {
    suspend fun extractConditionals(audioFilePath: String): Result<Unit>
}
