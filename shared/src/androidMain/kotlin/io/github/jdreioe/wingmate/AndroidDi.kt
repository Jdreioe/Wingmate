package io.github.jdreioe.wingmate

import android.content.Context
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.chatterbox.ModelRepository
import io.github.jdreioe.wingmate.domain.chatterbox.VoiceProfileRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSpeechService
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.infrastructure.chatterbox.FileSystemModelRepository
import io.github.jdreioe.wingmate.infrastructure.chatterbox.FileSystemVoiceProfileRepository
import io.github.jdreioe.wingmate.platform.AudioRecorder
import io.github.jdreioe.wingmate.platform.InferenceEngine
import io.github.jdreioe.wingmate.platform.PlatformAudioPlayer
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

fun overrideAndroidSpeechService(context: Context) {
    loadKoinModules(
        module {
            single<Context> { context }
            single<SpeechService> { AndroidSpeechService(context) }
            single<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider> { io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider() }
            // Prefer SQLite-backed repositories on Android for parity with desktop
            single<io.github.jdreioe.wingmate.domain.ConfigRepository> { AndroidSqlConfigRepository(context) }
            // Audio clipboard support
            single<io.github.jdreioe.wingmate.platform.AudioClipboard> { io.github.jdreioe.wingmate.platform.AndroidAudioClipboard(context) }
            // Share service for Android share sheet
            single<io.github.jdreioe.wingmate.platform.ShareService> { io.github.jdreioe.wingmate.platform.AndroidShareService(context) }
            single<io.github.jdreioe.wingmate.domain.PhraseRepository> { AndroidSqlPhraseRepository(context) }
            single<io.github.jdreioe.wingmate.domain.CategoryRepository> { AndroidSqlCategoryRepository(context) }
            single<io.github.jdreioe.wingmate.domain.VoiceRepository> { AndroidSqlVoiceRepository(context) }
            single<io.github.jdreioe.wingmate.domain.SettingsRepository> { AndroidSqlSettingsRepository(context) }
            single<io.github.jdreioe.wingmate.domain.SaidTextRepository> { AndroidSqlSaidTextRepository(context) }
            // Text prediction service using n-grams trained on user's history
            single<io.github.jdreioe.wingmate.domain.FileStorage> { io.github.jdreioe.wingmate.infrastructure.AndroidFileStorage(context) }
            single<io.github.jdreioe.wingmate.platform.FilePicker> { io.github.jdreioe.wingmate.platform.AndroidFilePicker(context) }
            single<io.github.jdreioe.wingmate.infrastructure.ImageCacher> { io.github.jdreioe.wingmate.infrastructure.AndroidImageCacher(context) }
            single<TextPredictionService> { SimpleNGramPredictionService() }

            // Chatterbox repositories (file-system backed)
            single<ModelRepository> { FileSystemModelRepository(get()) }
            single<VoiceProfileRepository> { FileSystemVoiceProfileRepository(get()) }

            // Chatterbox platform services
            single<InferenceEngine> { InferenceEngine() }
            single<AudioRecorder> { AudioRecorder(context) }
            single<PlatformAudioPlayer> { PlatformAudioPlayer() }
        }
    )
}
