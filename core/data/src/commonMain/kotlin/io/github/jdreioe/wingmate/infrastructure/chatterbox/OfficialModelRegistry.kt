package io.github.jdreioe.wingmate.infrastructure.chatterbox

import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxModel
import io.github.jdreioe.wingmate.domain.chatterbox.ModelArtifact
import io.github.jdreioe.wingmate.domain.chatterbox.ModelSource

object OfficialModelRegistry {
    const val Q4_MODEL_ID = "chatterbox-multilingual-onnx-q4"
    const val REVISION = "452d3f434aa592098f1eedac9099f33642ab2da5"
    const val Q4_TOTAL_SIZE_BYTES = 1_555_820_320L

    private const val BASE =
        "https://huggingface.co/onnx-community/chatterbox-multilingual-ONNX/resolve/$REVISION"

    val validatedLanguages = listOf("en", "da")
    val upstreamLanguages = listOf(
        "ar", "da", "de", "el", "en", "es", "fi", "fr", "he", "hi", "it", "ja",
        "ko", "ms", "nl", "no", "pl", "pt", "ru", "sv", "sw", "tr", "zh",
    )

    val q4Artifacts = listOf(
        artifact("onnx/speech_encoder.onnx", 1_184_608, "8f1c8a0f89b77bf9cd5dd8f2e034eb2c79dc00fe70d41196b28c257643b00ccb"),
        artifact("onnx/speech_encoder.onnx_data", 591_274_880, "92f8f290fc9720e169bc2412c507209e20b03f6564bc3243739e25c56f7dfb8f"),
        artifact("onnx/conditional_decoder.onnx", 6_350_448, "1656d0d31332bae1854839959a3139300ebb67c178651dfa3f8c5fbfa5351351"),
        artifact("onnx/conditional_decoder.onnx_data", 533_970_816, "51d58345a272747665ec9d5bb61e01835258a940e321a288582ac4c18cf01b5a"),
        artifact("onnx/embed_tokens.onnx", 13_286, "f785819ca4f6271262d5bb8971d62796c3a909e3b031982c113dbe83a4c3b854"),
        artifact("onnx/embed_tokens.onnx_data", 68_390_912, "2a15f7dd73b2ee47f6edf87740324011594b5a528ed6471ae55e327ed6cad68c"),
        artifact("onnx/language_model_q4.onnx", 227_911, "7f8cdca83b2493536cbf3acf421199808a3d68736f55f4eabd20ef8a99da4313"),
        artifact("onnx/language_model_q4.onnx_data", 353_621_248, "e79ab8784122a501718868b9631ff46e151c552d9b24e50f25d721f375e3526c"),
        artifact("tokenizer.json", 71_798, "29d48c4a178f6af3ad5130097c34744639e9294847b38a7b912c8c68027cb819"),
        artifact("generation_config.json", 93, "1b6fbb953861089ebe7da64df46eeef570d53f47a44b7cc1b4d543669fc9cd50"),
        artifact("default_voice.wav", 714_320, "3ebc531cdaba358a327099c1c4f0448026719957bcf4d8e9868767f227e02f4e"),
    )

    init {
        check(q4Artifacts.sumOf { it.sizeBytes } == Q4_TOTAL_SIZE_BYTES)
    }

    val models = listOf(
        ChatterboxModel(
            id = Q4_MODEL_ID,
            name = "Chatterbox Multilingual Q4",
            version = REVISION.take(8),
            sizeBytes = Q4_TOTAL_SIZE_BYTES,
            source = ModelSource.Official,
            modelFileUrl = BASE,
            languages = validatedLanguages,
            requiresGpu = false,
        )
    )

    fun getModel(modelId: String): ChatterboxModel? = models.firstOrNull { it.id == modelId }

    fun getModelArtifacts(modelId: String): List<ModelArtifact> = when (modelId) {
        Q4_MODEL_ID -> q4Artifacts
        else -> emptyList()
    }

    fun getModelFiles(modelId: String): List<String> =
        getModelArtifacts(modelId).map { it.relativePath }

    fun getModelFileUrl(modelId: String, path: String): String =
        getModelArtifacts(modelId).firstOrNull { it.relativePath == path }?.url
            ?: error("Unknown Chatterbox artifact: $modelId/$path")

    private fun artifact(path: String, size: Long, sha256: String) =
        ModelArtifact(path, size, sha256, "$BASE/$path")
}
