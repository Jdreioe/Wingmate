# I-01 (#54) Implementation Plan — TTS Engine Model

## Files to Modify

### 1. `core/domain/src/commonMain/kotlin/io/github/jdreioe/wingmate/domain/model.kt`
- Add `TtsEngine` enum after `StartupMode`:
  ```kotlin
  @Serializable
  enum class TtsEngine {
      SYSTEM,
      AZURE_USER_RESOURCE,
      AZURE_MANAGED
  }
  ```
- In `Settings` data class: Remove `val useSystemTts: Boolean = false`, add `val ttsEngine: TtsEngine = TtsEngine.SYSTEM`

### 2. `core/presentation/src/commonMain/kotlin/io/github/jdreioe/wingmate/application/SettingsStateManager.kt`
- Add `ConfigRepository` as constructor parameter (2nd param)
- In `init{}`: Add migration logic:
  - Read raw JSON stored by repo, detect `useSystemTts` key absence → check if migration needed
  - If old format detected: `useSystemTts: true` → `SYSTEM`; `false` → check `configRepo.getSpeechConfig()` for non-blank endpoint+key → `AZURE_USER_RESOURCE` or `SYSTEM`
  - Save migrated settings back

### 3. `shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/di.kt`
- Update `singleOf(::SettingsStateManager)` to pass both repos (or use constructor with both params)

### 4. Compose UI files — replace `useSystemTts` boolean with `ttsEngine` enum comparisons
Pattern: `!useSystemTts` → `ttsEngine != TtsEngine.SYSTEM`, `useSystemTts` → `ttsEngine == TtsEngine.SYSTEM`

Files:
- `composeApp/.../ui/AzureSettingsFullScreen.kt`
- `composeApp/.../ui/AzureSettingsDialog.kt`
- `composeApp/.../ui/AzureConfigScreen.kt`
- `composeApp/.../ui/SettingsScreen.kt`
- `composeApp/.../ui/VoiceSelectionDialog.kt`
- `composeApp/.../ui/VoiceSettingsDialog.kt`
- `composeApp/.../ui/VoiceSelectionFullScreen.kt`
- `composeApp/.../ui/VoiceEngineSelectorScreen.kt`
- `composeApp/.../ui/SsmlSidebar.kt`

### 5. Platform speech services
- `core/data/src/androidMain/.../AndroidSpeechService.kt`: `uiSettings?.useSystemTts == true` → `uiSettings?.ttsEngine == TtsEngine.SYSTEM`
- `composeApp/src/desktopMain/.../DesktopSpeechService.kt`: Same

### 6. Linux KDE
- `linuxApp/src/main/kotlin/.../KotlinBridge.kt`: Replace `useSystemTts` with `ttsEngine`
- `linuxApp/src/main.qml`: Replace `property bool useSystemTts` with `property string ttsEngine`, update all refs
- `linuxApp/src/pages/SettingsPage.qml`: Same

### 7. Tests
- `shared/src/commonTest/` or wherever tests go: Add unit tests for Settings serialization/migration

### 8. Build & verify
- `infisical run -- ./gradlew :shared:test`
- `./gradlew :desktopApp:run` (manual smoke test)

## Migration Logic (in SettingsStateManager)
```kotlin
init {
    scope.launch {
        val initialSettings = settingsRepository.get()
        // Detect old format by checking if ttsEngine == SYSTEM AND 
        // we can find "useSystemTts" in the raw data
        // Since we can't read raw JSON from the repo interface,
        // we need a different approach
        
        // Approach: Always check if this is a fresh Settings() default
        // vs migrated data. We'll add a flag or use ConfigRepository.
        
        val config = configRepository.getSpeechConfig()
        val hasAzureCredentials = config != null && 
            config.endpoint.isNotBlank() && 
            config.subscriptionKey.isNotBlank()
        
        // For existing users who had useSystemTts=false and have credentials,
        // we need to set ttsEngine = AZURE_USER_RESOURCE
        // For all others, ttsEngine = SYSTEM (default)
        
        // We detect "old format" users by checking if they have Azure credentials
        // but ttsEngine is SYSTEM (the new default)
        // This is a heuristic but works for the migration
        
        if (hasAzureCredentials && initialSettings.ttsEngine == TtsEngine.SYSTEM) {
            // This user likely had useSystemTts=false with Azure creds
            val migrated = initialSettings.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE)
            settingsRepository.update(migrated)
            _settings.value = migrated
        } else {
            _settings.value = initialSettings
        }
    }
}
```

Note: This heuristic approach works because:
- New installs: no credentials → `SYSTEM` (correct)
- Old installs with `useSystemTts=true`: no Azure progress → `SYSTEM` (correct)
- Old installs with `useSystemTts=false` + Azure creds: has creds → `AZURE_USER_RESOURCE` (correct)
- Old installs with `useSystemTts=false` but no saved creds: no creds → `SYSTEM` (they'll need to re-enter creds)
