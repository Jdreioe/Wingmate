package io.github.jdreioe.wingmate.domain.chatterbox

interface SpeechVerifier {
    suspend fun verify(languageCode: String): Result<String>
    suspend fun verifyFromFile(audioFilePath: String, languageCode: String): Result<String>
}
