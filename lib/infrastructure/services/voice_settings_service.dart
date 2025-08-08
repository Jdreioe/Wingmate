import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/domain/entities/voice.dart' as domain_models;
import 'package:wingmate/infrastructure/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/infrastructure/data/said_text_dao.dart';
import 'package:wingmate/infrastructure/data/voice_dao.dart';
import 'package:wingmate/infrastructure/config/speech_service_config.dart';

class VoiceSettingsService {
  final Box<dynamic> _voiceBox;
  final Box<dynamic> _settingsBox;
  final AzureTts _azureTts;
  final VoiceDao _voiceDao;
  final String _endpoint;
  final String _subscriptionKey;
  final Function(String, String) _onSaveSettings;

  VoiceSettingsService({
    required Box<dynamic> voiceBox,
    required Box<dynamic> settingsBox,
    required String endpoint,
    required String subscriptionKey,
    required Function(String, String) onSaveSettings,
    required BuildContext context,
  })  : _voiceBox = voiceBox,
        _settingsBox = settingsBox,
        _endpoint = endpoint,
        _subscriptionKey = subscriptionKey,
        _onSaveSettings = onSaveSettings,
        _voiceDao = VoiceDao(AppDatabase()),
        _azureTts = AzureTts(
          subscriptionKey: subscriptionKey,
          region: endpoint,
          settingsBox: settingsBox,
          voiceBox: voiceBox,
          context: context,
          saidTextDao: SaidTextDao(AppDatabase()),
        );

  Future<void> saveSettings(String endpoint, String key) async {
    await _onSaveSettings(endpoint, key);
  }

  Future<void> testVoice(domain_models.Voice voice) async {
    await _azureTts.testVoice('This is a test of the ${voice.name} voice.', voice);
  }

  Future<void> saveVoice(domain_models.Voice voice) async {
    final voiceModel = voice;
    await _voiceBox.put('currentVoice', voiceModel);
  }

  domain_models.Voice? getSelectedVoice() {
    return _voiceBox.get('currentVoice') as domain_models.Voice?;
  }

  Future<List<domain_models.Voice>> getVoices() async {
    final voiceItems = await _voiceDao.getVoices();
    return voiceItems.map((item) => domain_models.Voice(
      name: item.name ?? '',
      supportedLanguages: item.supportedLanguages ?? [],
      primaryLanguage: item.primaryLanguage ?? '',
      pitch: item.pitch ?? 0.0,
      rate: item.rate ?? 0.0,
      pitchForSSML: item.pitchForSSML ?? 'medium',
      rateForSSML: item.rateForSSML ?? 'medium',
      selectedLanguage: item.primaryLanguage ?? 'en-US',
    )).toList();
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
 