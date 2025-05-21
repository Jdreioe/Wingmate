import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'profile_service.dart'; // Added import

class VoiceSettingsService {
  final Box<dynamic> _voiceBox;
  final Box<dynamic> _settingsBox;
  final AzureTts _azureTts;
  final String _endpoint;
  final String _subscriptionKey;
  final Function(String, String) _onSaveSettings;
  final ProfileService _profileService; // Added ProfileService

  VoiceSettingsService({
    required Box<dynamic> voiceBox,
    required Box<dynamic> settingsBox,
    required String endpoint,
    required String subscriptionKey,
    required Function(String, String) onSaveSettings,
    required BuildContext context,
    required ProfileService profileService, // Added to constructor
  })  : _voiceBox = voiceBox,
        _settingsBox = settingsBox,
        _endpoint = endpoint,
        _subscriptionKey = subscriptionKey,
        _onSaveSettings = onSaveSettings,
        _profileService = profileService, // Initialize ProfileService
        _azureTts = AzureTts(
          subscriptionKey: subscriptionKey,
          region: endpoint,
          settingsBox: settingsBox,
          messageController: TextEditingController(),
          voiceBox: voiceBox,
          context: context,
          // Pass methods to get profile settings
          getActiveVoiceName: getActiveVoiceName,
          getActiveLanguageCode: getActiveLanguageCode,
          getActiveSpeechRate: getActiveSpeechRate,
          getActivePitch: getActivePitch,
        );

  Future<void> saveSettings(String endpoint, String key) async {
    await _onSaveSettings(endpoint, key);
  }

  Future<void> testVoice(Voice voice) async {
    // AzureTts will now use its internally configured getters which access profile settings.
    // We might simplify the SSML construction if AzureTts takes more direct responsibility.
    // For now, just calling generateSSML should be sufficient if it's adapted.
    // However, to be explicit about using profile settings for *this specific test call*,
    // we can build SSML here.
    final voiceName = await getActiveVoiceName() ?? voice.name;
    final languageCode = await getActiveLanguageCode() ?? voice.locale;
    final speechRate = await getActiveSpeechRate() ?? 1.0; // Default if not set
    final pitch = await getActivePitch() ?? 1.0; // Default if not set

    final ssml = '''<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="$languageCode">
                        <voice name="$voiceName">
                            <prosody rate="${speechRate}" pitch="${pitch}">
                                This is a test of the $voiceName voice.
                            </prosody>
                        </voice>
                    </speak>''';
    await _azureTts.generateSSML(ssml, useProfileSettings: true); // Added flag for clarity
  }

  Future<void> saveVoice(Voice voice) async {
    final activeProfile = await _profileService.getActiveProfile();
    if (activeProfile != null && activeProfile.id != null) {
      final updatedProfile = activeProfile.copyWith(
        voiceName: voice.name,
        languageCode: voice.locale, // Assuming locale maps to languageCode
      );
      await _profileService.updateProfile(updatedProfile);
    } else {
      // Fallback or if profile doesn't have an ID (e.g., not saved yet)
      await _voiceBox.put('currentVoice', voice);
    }
  }

  Future<Voice?> getSelectedVoice() async {
    final activeProfile = await _profileService.getDefaultProfile();
    if (activeProfile != null) {
      return Voice(
        name: activeProfile.voiceName,
        locale: activeProfile.languageCode,
        displayName: activeProfile.voiceName, // Consider enhancing
        gender: '', // Needs a source
        supportedLanguages: '', // Needs a source
      );
    }
    return _voiceBox.get('currentVoice') as Voice?; // Fallback
  }

  Future<String?> getActiveVoiceName() async {
    final profile = await _profileService.getDefaultProfile();
    return profile?.voiceName;
  }

  Future<String?> getActiveLanguageCode() async {
    final profile = await _profileService.getDefaultProfile();
    return profile?.languageCode;
  }

  Future<double?> getActiveSpeechRate() async {
    final profile = await _profileService.getDefaultProfile();
    return profile?.speechRate;
  }

  Future<double?> getActivePitch() async {
    final profile = await _profileService.getDefaultProfile();
    return profile?.pitch;
  }

  Future<void> saveSpeechServiceConfig(SpeechServiceConfig config) async {
    await _settingsBox.put('config', config);
  }

  SpeechServiceConfig? getSpeechServiceConfig() {
    return _settingsBox.get('config') as SpeechServiceConfig?;
  }

  String get endpoint => _endpoint;
  String get subscriptionKey => _subscriptionKey;
} 