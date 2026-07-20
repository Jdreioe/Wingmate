package io.github.jdreioe.wingmate.domain.chatterbox

interface AudioExtractor {
    suspend fun extractToWav(inputPath: String, outputPath: String): Result<String>
}