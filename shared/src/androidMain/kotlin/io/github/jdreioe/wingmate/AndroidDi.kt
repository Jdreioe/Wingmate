package io.github.jdreioe.wingmate

import android.content.Context
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.infrastructure.AndroidFileStorage
import io.github.jdreioe.wingmate.infrastructure.AndroidBoardRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidAptabaseFeatureUsageReporter
import io.github.jdreioe.wingmate.infrastructure.AndroidSoundPlayer
import io.github.jdreioe.wingmate.infrastructure.AndroidSpeechService
import io.github.jdreioe.wingmate.infrastructure.AndroidGoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.GoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.AndroidImageCacher
import io.github.jdreioe.wingmate.infrastructure.AndroidPhraseRecordingService
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidPreferencesCommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlPronunciationDictionaryRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSystemVoiceProvider
import io.github.jdreioe.wingmate.infrastructure.AndroidSecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.BackupMediaAccess
import io.github.jdreioe.wingmate.infrastructure.AndroidBackupMediaAccess
import io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.platform.AndroidAudioClipboard
import io.github.jdreioe.wingmate.platform.AndroidFilePicker
import io.github.jdreioe.wingmate.platform.AndroidShareService
import io.github.jdreioe.wingmate.platform.AudioClipboard
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import okio.FileSystem
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Android platform bindings (SQLite persistence, speech, sharing, files,
 * credentials, and optional feature usage reporting), overriding the defaults
 * from [io.github.jdreioe.wingmate.di.sharedModules].
 */
fun androidModule(context: Context, aptabaseAppKey: String): Module = module {
    single<Context> { context }
    single<GoogleApiRequestHeaders> { AndroidGoogleApiRequestHeaders(context) }
    singleOf(::AndroidSpeechService) { bind<SpeechService>() }
    singleOf(::AndroidPhraseRecordingService) { bind<PhraseRecordingService>() }
    singleOf(::AndroidSystemVoiceProvider) { bind<SystemVoiceProvider>() }
    singleOf(::AndroidSqlConfigRepository) { bind<ConfigRepository>() }
    singleOf(::AndroidPreferencesCommunicationSessionDataSource) { bind<CommunicationSessionDataSource>() }
    singleOf(::AndroidAudioClipboard) { bind<AudioClipboard>() }
    singleOf(::AndroidShareService) { bind<ShareService>() }
    singleOf(::AndroidSqlPhraseRepository) { bind<PhraseRepository>() }
    singleOf(::AndroidSqlVoiceRepository) { bind<VoiceRepository>() }
    singleOf(::AndroidSqlSettingsRepository) { bind<SettingsRepository>() }
    singleOf(::AndroidSqlPronunciationDictionaryRepository) { bind<PronunciationDictionaryRepository>() }
    singleOf(::AndroidSqlSaidTextRepository) { bind<SaidTextRepository>() }
    singleOf(::AndroidBoardRepository) { bind<BoardRepository>() }
    singleOf(::AndroidBoardSetRepository) { bind<BoardSetRepository>() }
    singleOf(::AndroidFileStorage) { bind<FileStorage>() }
    singleOf(::AndroidSoundPlayer) { bind<SoundPlayer>() }
    singleOf(::AndroidFilePicker) { bind<FilePicker>() }
    singleOf(::AndroidSecureEditingCredentialStorage) { bind<SecureEditingCredentialStorage>() }
    singleOf(::AndroidBackupMediaAccess) { bind<BackupMediaAccess>() }
    singleOf(::AndroidImageCacher) { bind<ImageCacher>() }
    single<FeatureUsageReporter> {
        AndroidAptabaseFeatureUsageReporter(
            context = context,
            settingsStateManager = get(),
            appKey = aptabaseAppKey
        )
    }
    single { FileSystem.SYSTEM }
    // Usage-log destination for RealAacLogger (app-private files dir)
    single(named("logDir")) { context.filesDir.absolutePath }
}
