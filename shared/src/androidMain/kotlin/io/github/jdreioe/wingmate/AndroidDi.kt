package io.github.jdreioe.wingmate

import android.content.Context
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.infrastructure.AndroidFileStorage
import io.github.jdreioe.wingmate.infrastructure.AndroidFirebaseFeatureUsageReporter
import io.github.jdreioe.wingmate.infrastructure.AndroidSpeechService
import io.github.jdreioe.wingmate.infrastructure.AndroidImageCacher
import io.github.jdreioe.wingmate.infrastructure.AndroidPhraseRecordingService
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.platform.AndroidAudioClipboard
import io.github.jdreioe.wingmate.platform.AndroidFilePicker
import io.github.jdreioe.wingmate.platform.AndroidShareService
import io.github.jdreioe.wingmate.platform.AudioClipboard
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

fun overrideAndroidSpeechService(context: Context) {
    loadKoinModules(
        module {
            single<Context> { context }
            singleOf(::AndroidSpeechService) { bind<SpeechService>() }
            singleOf(::AndroidPhraseRecordingService) { bind<PhraseRecordingService>() }
            singleOf(::SystemVoiceProvider)
            // Prefer SQLite-backed repositories on Android for parity with desktop
            singleOf(::AndroidSqlConfigRepository) { bind<ConfigRepository>() }
            // Audio clipboard support
            singleOf(::AndroidAudioClipboard) { bind<AudioClipboard>() }
            // Share service for Android share sheet
            singleOf(::AndroidShareService) { bind<ShareService>() }
            singleOf(::AndroidSqlPhraseRepository) { bind<PhraseRepository>() }
            singleOf(::AndroidSqlCategoryRepository) { bind<CategoryRepository>() }
            singleOf(::AndroidSqlVoiceRepository) { bind<VoiceRepository>() }
            singleOf(::AndroidSqlSettingsRepository) { bind<SettingsRepository>() }
            singleOf(::AndroidSqlSaidTextRepository) { bind<SaidTextRepository>() }
            // Text prediction service using n-grams trained on user's history
            singleOf(::AndroidFileStorage) { bind<FileStorage>() }
            singleOf(::AndroidFilePicker) { bind<FilePicker>() }
            singleOf(::AndroidImageCacher) { bind<ImageCacher>() }
            singleOf(::SimpleNGramPredictionService) { bind<TextPredictionService>() }
            singleOf(::AndroidFirebaseFeatureUsageReporter) { bind<FeatureUsageReporter>() }
        }
    )
}
