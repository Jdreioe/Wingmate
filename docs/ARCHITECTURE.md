# Architecture

This document is the codebase map for Wingmate. Read [CONTEXT.md](../CONTEXT.md) for product vocabulary first, then use this guide to locate files, boundaries, and verification commands quickly.

## Module Layout

Wingmate is a Kotlin Multiplatform repository with native Android (Jetpack Compose) and iOS (SwiftUI) clients plus a small Cloudflare Worker proxy for symbol search.

```text
androidApp/ (Jetpack Compose)          iosApp/ (SwiftUI, links Shared.framework)
           \                                       /
            \                                     /
             v                                   v
                         :shared
         (facades, application use cases, Koin DI)
            /                |                \
           v                 v                 v
  :core:domain          :core:data      :core:presentation
  (models, ports,      (SQLite, iOS     (SettingsStateManager)
   OBF/policy logic)    persistence,
                        TTS, parsers)
            \                |                /
             v               v               v
          :feature:communication:{domain,data,presentation}
          (Message session, Phrase CRUD, voice catalogs, PhraseListStore)
```

- `:core:domain`: Platform-independent domain models (`Message`, `Phrase`, `Settings`, `Voice`, `ObfBoardSet`, `ObfBoard`), repository/service ports, and pure policies (`LikelyNext`, `TextEditingPolicy`, `BoardSessionLogic`, `BoardPrintLayout`, `AccessInputController`, `SelectionHighlight`, `WordTypeColors`).
- `:core:data`: Platform persistence (`AndroidSql*`, `Ios*`), cloud TTS clients (`AzureTtsClient`, `GoogleTtsClient`, `ElevenLabsTtsClient`), `AndroidSpeechService`, `IosSpeechService`, OBF/OBZ parser/validator, local text prediction (`LocalTextPredictionService`), and symbol search clients (`SymbolSearchClient`, `OpenSymbolsClient`).
- `:core:presentation`: Shared reactive settings state (`SettingsStateManager`).
- `:feature:communication:{domain,data,presentation}`: `CommunicationSession` / `QueuedCommunicationSession` (active and held Message state, playback queue, History recording), Phrase CRUD use cases, cloud voice catalogs, and `PhraseListStore` (MVIKotlin store used by iOS).
- `:shared`: Application use cases (`BoardSetUseCase`, `TypingScreenUseCase`, `CompleteBackupManager`, `EditingAccessController`, `ObzExporter`, `GridsetExporter`), native feature facades (`*Facade.kt`), unified Koin DI (`di/SharedModules.kt`, `di.kt`, `AndroidDi.kt`, `IosDi.kt`), and the iOS `Shared.framework` export boundary (`shared/build.gradle.kts`).
- `:androidApp`: Jetpack Compose app (`androidApp/src/main/java/io/github/jdreioe/wingmate/`).
- `iosApp/`: SwiftUI app (`iosApp/iosApp/`). Uses `PBXFileSystemSynchronizedRootGroup`, so adding/moving Swift files under `iosApp/iosApp/` does not require manual `.pbxproj` edits.
- `infra/opensymbols-proxy/`: Cloudflare Worker proxying OpenSymbols searches.

## Terminology Bridge (`CONTEXT.md` -> Codebase)

Domain documentation uses the ubiquitous language in [CONTEXT.md](../CONTEXT.md). Many older files and types still carry pre-glossary names; use this table when searching the codebase, and prefer `CONTEXT.md` terms for new code.

| `CONTEXT.md` term | Current code symbols & files |
| --- | --- |
| **Screen** *(avoid: Board set)* | `ObfBoardSet`, `BoardSetRepository`, `BoardSetUseCase`, `BoardsFacade`, `ui/screens/*` (`BoardSetLibraryScreen.kt`, `BoardSetWorkspaceRoot.kt`), `BoardSetManagerViewModel.kt`, `BoardSetDialogs.kt`, `Views/Screens/*` (`SymbolBoardWorkspaceView.swift`) |
| **Page** *(avoid: Board)* | `ObfBoard`, `BoardRepository`, `ui/screens/*` (`ObfBoardView.kt`, `SpanningBoardGrid.kt`), `BoardSettingsScreen.kt`, `BoardSettingsSheet.swift` |
| **Page element** | `PageElements.kt` (`PhraseCollection`, `PageNavigation`, `ActionStrip`, `ObfButton`) |
| **Button / Grid / Cell** | `ObfButton`, `ObfGrid`, `ObfGridFields.kt` (`fieldItems`, `moveOrSwapField`, `withFieldSpan`) |
| **Typing workspace** | `ui/typing/*` (`PhraseScreen.kt`), `PhraseScreenViewModel.kt`, `WideTypingLayout.kt`, `TypingWorkspaceView.swift` |
| **Typing Screen** *(avoid: Phrase screen)* | `TypingScreen.kt` (`TypingScreenUseCase`), `TypingScreenFacade.kt`, `PhraseBoard.kt`, `TypingTrayView.swift` |
| **Phrase / Category** | `Phrase`, `FolderPhrase` (`PhraseTree.kt`), `CategoryItem`, `PhraseUseCase`, `CategoryUseCase`, `PhraseListStore` |
| **Message / Message part / Held message** | `Message`, `MessagePart`, `CommunicationSession`, `QueuedCommunicationSession`, `CommunicationSessionFacade` (note legacy Swift property `hasHeldThought` in `IosViewModel.swift`) |
| **History** | `SaidText`, `SaidTextRepository`, `AndroidSqlSaidTextRepository`, `IosSaidTextRepository`, `CommunicationFacade` |
| **Likely next Page** | `LikelyNext.kt` (`likelyNextMessages`), `PhraseScreenViewModel.kt`, `CommunicationFacade.kt` |
| **Editing access / Screen lock** | `EditingAccessController`, `EditingAccessStore`, `EditingAccessDialog.kt`, `EditingAccessPinSheet.swift`, `EditingAccessUnlockSheet.swift`, `ObfBoardSet.isLocked` |
| **Backup** | `BackupManager`, `CompleteBackupManager`, `BackupFacade`, `BackupSharingFacade` |
| **Vocabulary package** | `ObzExporter`, `GridsetExporter`, `BoardImportService`, `ObfParser`, `ObfValidator` |

## Feature-to-File Map

Use this map when implementing or tracing cross-platform changes so Android and iOS stay in parity.

| Feature | Shared (Domain / Data / Application) | Android (`androidApp/`) | iOS (`iosApp/iosApp/`) | Key Tests |
| --- | --- | --- | --- | --- |
| **Message & Communication session** | `domain/Message.kt`, `domain/CommunicationSession.kt`, `application/QueuedCommunicationSession.kt`, `application/CommunicationSessionFacade.kt` | `App.kt`, `ui/CommunicationSessionStatus.kt`, `ui/PhraseScreenViewModel.kt`, `ui/screens/BoardSetWorkspaceRoot.kt` | `ViewModels/IosViewModel+MessageAndTyping.swift`, `Views/MessageBar.swift`, `Views/CommunicationNoticeBanner.swift` | `QueuedCommunicationSessionTest.kt`, `CommunicationSessionFacadeTest.kt`, `CommunicationSessionAdaptersTest.kt` |
| **Typing workspace & Typing Screen** | `domain/PhraseTree.kt`, `domain/LikelyNext.kt`, `domain/TextEditingPolicy.kt`, `application/TypingScreen.kt`, `application/TypingScreenFacade.kt`, `application/CommunicationFacade.kt`, `application/bloc/PhraseListStoreFactory.kt` | `ui/typing/*` (`PhraseScreen.kt`, `PhraseScreenDialogs.kt`, `PhraseScreenComponents.kt`), `ui/PhraseScreenViewModel.kt`, `ui/PhraseBoard.kt`, `ui/WideTypingLayout.kt`, `ui/AddPhraseDialog.kt`, `ui/PredictionBar.kt` | `Views/TypingWorkspaceView.swift`, `Views/TypingTrayView.swift`, `Views/PredictionBar.swift`, `Sheets/{AddCategorySheet,AddPhraseSheet,EditPhraseSheet}.swift`, `ViewModels/IosViewModel+MessageAndTyping.swift` | `TypingScreenUseCaseTest.kt`, `TypingScreenFacadeTest.kt`, `TypingScreenProjectorTest.kt`, `PhraseScreenViewModelTest.kt`, `CommunicationFacadeTest.kt` |
| **Screens workspace** | `domain/obf/*`, `application/BoardSetUseCase.kt`, `application/BoardsFacade.kt`, `application/ObzExporter.kt`, `application/GridsetExporter.kt`, `infrastructure/BoardImportService.kt` | `ui/screens/*` (`BoardSetLibraryScreen.kt`, `BoardSetWorkspaceRoot.kt`, `BoardSetWorkspaceTopBar.kt`, `BoardSetWorkspaceActions.kt`, `BoardSetWorkspaceDialogs.kt`, `BoardWorkspaceContent.kt`, `ObfBoardView.kt`, `SpanningBoardGrid.kt`, `ObfButtonContent.kt`, `BoardWorkspaceControls.kt`), `ui/BoardSetManagerViewModel.kt`, `ui/BoardWorkspaceState.kt`, `ui/BoardSettingsScreen.kt`, `ui/BoardSetDialogs.kt`, `ui/BoardPdf.kt` | `Views/Screens/*` (`SymbolBoardWorkspaceView.swift`, `ScreensLibraryView.swift`, `ScreensWorkspaceControls.swift`, `ScreensGridCanvas.swift`, `ScreensManagementSheets.swift`, `ScreensWorkspaceActions.swift`), `Views/BoardSettingsSheet.swift`, `Support/ScreenMedia.swift`, `ViewModels/IosViewModel+{ScreensLibrary,ScreensBoard}.swift` | `BoardSetUseCaseTest.kt`, `BoardsFacadeTest.kt`, `ObzExporterTest.kt`, `GridsetExporterTest.kt`, `BoardImportServiceTest.kt`, `BoardSetManagerViewModelTest.kt` |
| **Speech, Voices & Pronunciation** | `domain/voice.kt`, `domain/SpeechTextProcessor.kt`, `application/SpeechFacade.kt`, `application/PronunciationFacade.kt`, `infrastructure/{Azure,Google,ElevenLabs}TtsClient.kt`, `infrastructure/speech/*` (`AndroidSpeechService.kt`, `AndroidSpeechCloudPlayback.kt`, `AndroidSpeechPlatformTts.kt`), `infrastructure/IosSpeechService.kt` | `ui/VoiceSelectionViewModel.kt`, `ui/VoiceSettingsDialog.kt`, `ui/TestVoiceScreen.kt`, `ui/DictionaryScreen.kt`, `ui/{ElevenLabs,GoogleTts,F0}SetupScreen.kt` | `Support/SystemTtsManager.swift`, `Views/PronunciationDictionaryView.swift`, `Views/F0SetupView.swift`, `Views/Settings/SpeechSettingsViews.swift`, `Sheets/VoiceAndLanguageSheets.swift`, `ViewModels/IosViewModel+Settings.swift` | `SpeechFacadeTest.kt`, `PronunciationFacadeTest.kt`, `SpeechTextProcessorTest.kt`, `VoiceSelectionViewModelTest.kt`, `SystemTtsManagerTests.swift` |
| **Settings, Editing access & Access methods** | `domain/model.kt`, `application/SettingsStateManager.kt`, `application/SettingsFacade.kt`, `application/EditingAccessController.kt`, `application/AccessInputController.kt`, `application/SelectionHighlight.kt` | `ui/settings/*` (`SettingsScreen.kt`, `SettingsHomePage.kt`, `SpeechAndDisplaySettingsSections.kt`, `AccessibilityPrivacyGeneralSections.kt`, `VoiceAndLanguageSettingsPages.kt`, `SettingsComponents.kt`), `ui/SettingsViewModel.kt`, `ui/ReactiveSettings.kt`, `ui/EditingAccessDialog.kt`, `ui/InteractionInput.kt`, `ui/TouchDownSelection.kt`, `ui/PageSwipe.kt` | `Views/Settings/*` (`SettingsView.swift`, `SpeechSettingsViews.swift`, `DisplayAndAccessibilitySettingsViews.swift`, `GeneralAndPrivacySettingsViews.swift`), `Views/EditingAccessPinSheet.swift`, `Views/EditingAccessUnlockSheet.swift`, `Modifiers/Modifiers.swift`, `ViewModels/IosViewModel+Settings.swift` | `SettingsFacadeTest.kt`, `EditingAccessControllerTest.kt`, `SettingsViewModelTest.kt`, `InteractionInputRootTest.kt` |
| **Backup & Restore** | `application/CompleteBackupManager.kt`, `application/BackupFacade.kt`, `infrastructure/{Android,Ios}BackupMediaAccess.kt` | `App.kt`, `ui/settings/AccessibilityPrivacyGeneralSections.kt`, `ui/SettingsViewModel.kt` | `Views/Settings/GeneralAndPrivacySettingsViews.swift`, `ViewModels/IosViewModel+Settings.swift` | `CompleteBackupManagerTest.kt`, `BackupFacadeTest.kt` |
| **Symbol search** | `infrastructure/SymbolSearchClient.kt`, `infrastructure/OpenSymbolsClient.kt`, `application/SymbolSearchFacade.kt`, `infra/opensymbols-proxy/` | `ui/OpenSymbolsSearchDialog.kt`, `ui/ImageSourcePickerDialog.kt` | `Sheets/{SymbolPickerModels,AddPhraseSheet,EditPhraseSheet}.swift`, `ViewModels/IosViewModel+MessageAndTyping.swift` | `SymbolSearchFacadeTest.kt`, `infra/opensymbols-proxy` (`npm test`) |
| **Welcome flow** | `application/SettingsFacade.kt`, `application/SpeechFacade.kt` | `App.kt`, `StartupLoadState.kt`, `ui/welcome/*` (`WelcomeScreen.kt`, `WelcomeSetupComponents.kt`, `WelcomeSetupPages.kt`), `ui/WelcomeNavigation.kt` | `Views/Welcome/*` (`WelcomeFlow.swift`, `WelcomeFlow+Steps.swift`, `WelcomeTtsProvider.swift`, `WelcomeCards.swift`), `ContentView.swift` | `StartupLoadStateTest.kt`, `WelcomeNavigationTest.kt` |
| **Partner / External display** | — | `MainActivity.kt`, `display/*`, `ui/FullScreenDisplay.kt` | `Support/PartnerDisplay.swift`, `Support/DuoLayout.swift` | `DuoLayoutTests.swift` |

## Dependency Injection (Koin)

All shared DI bindings live in one place: [shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/di/SharedModules.kt](../shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/di/SharedModules.kt), split by feature (`coreDefaultsModule`, `speechModule`, `settingsModule`, `typingModule`, `communicationModule`, `screensModule`, `backupModule`) and composed via `sharedModules()`.

- **Entry point**: [shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/di.kt](../shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/di.kt) exposes `initKoin(vararg platformModules: Module)`.
- **Android composition root**: [shared/src/androidMain/kotlin/io/github/jdreioe/wingmate/AndroidDi.kt](../shared/src/androidMain/kotlin/io/github/jdreioe/wingmate/AndroidDi.kt) builds `androidModule(context, aptabaseAppKey)`. [WingmateApplication.kt](../androidApp/src/main/java/io/github/jdreioe/wingmate/WingmateApplication.kt) passes it directly to `initKoin(...)` once at startup.
- **iOS composition root**: [shared/src/iosMain/kotlin/io/github/jdreioe/wingmate/IosDi.kt](../shared/src/iosMain/kotlin/io/github/jdreioe/wingmate/IosDi.kt) builds `iosModule(deviceSpeech, aptabaseEndpoint)` and exposes `IosDiBridge`, the only place Swift resolves Koin bindings.
- **Wiring verification**: [shared/src/commonTest/kotlin/KoinWiringTest.kt](../shared/src/commonTest/kotlin/KoinWiringTest.kt) verifies that every public facade and core shared binding resolves cleanly from `sharedModules()`. Whenever you add a facade or change a constructor wired by Koin, update `SharedModules.kt` and `KoinWiringTest.kt` together.

## Platform Boundary Conventions & Known Asymmetries

- **Shared business logic first**: Put domain rules, policies, state machines, formatting, and export/import logic in Kotlin (`:core:domain`, `:core:data`, or `:shared`) so `jvmTest` covers both clients in seconds.
- **Current Android vs iOS asymmetry**:
  - iOS talks to Kotlin exclusively through `*Facade` classes in `:shared` (plus `PhraseListStore` for the Phrase list) and receives state updates via `NativeSubscription`.
  - Android mostly injects use cases/repositories directly in ViewModels and Composables, though newer flows (e.g., cloud TTS setup) use shared facades. Prefer routing new cross-platform workflows through shared facades/controllers rather than duplicating logic in `androidApp` and `iosApp`.
- **Keep files focused**: Prefer extracting new dialogs, sheets, or sections into focused feature-folder files (as in `ui/settings/`, `ui/screens/`, `ui/welcome/`, `ui/typing/`, `infrastructure/speech/`, `Views/Screens/`, `Views/Welcome/`, `Views/Settings/`, `Sheets/`, and `ViewModels/`) instead of growing large files.
- **iOS framework exports**: Swift can only see types from `:shared` and modules explicitly passed to `export(...)` in [shared/build.gradle.kts](../shared/build.gradle.kts) (`:core:domain` and `:feature:communication:presentation`).

## Verification Loops

Use the fastest relevant loop while iterating, then run the full platform gate before finishing:

- **Single shared test**: `./gradlew :shared:jvmTest --tests "*KoinWiringTest*"` (or `:core:domain:jvmTest`, `:core:data:jvmTest`, `:feature:communication:presentation:jvmTest`)
- **All shared Kotlin tests**: `./gradlew jvmTest`
- **Android unit tests only**: `./gradlew :androidApp:testDebugUnitTest`
- **Full Android gate** (build, lint, unit tests, `jvmTest`): `bash ci_scripts/verify_android.sh`
- **Android instrumentation tests** (needs emulator/device): `./gradlew :androidApp:connectedDebugAndroidTest`
- **iOS compile-only check** (macOS, no simulator boot): `WINGMATE_IOS_BUILD_ONLY=1 bash ci_scripts/verify_ios.sh`
- **Full iOS gate** (macOS, localization check + simulator tests): `bash ci_scripts/verify_ios.sh`
- **iOS localization check only**: `python3 ci_scripts/check_ios_localizations.py`
- **Client secret boundary check**: `bash ci_scripts/check_client_secret_boundary.sh`
- **OpenSymbols worker**: `npm test && npm run check` in `infra/opensymbols-proxy`
