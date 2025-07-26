import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/data/app_database.dart';
import 'package:wingmate/data/said_text_dao.dart';
import 'package:wingmate/config/speech_service_config.dart';

class VoiceSettingsService {
  final Box<dynamic> _voiceBox;
  final Box<dynamic> _settingsBox;
  final AzureTts _azureTts;
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

  Future<void> testVoice(Voice voice) async {
    await _azureTts.testVoice('This is a test of the ${voice.name} voice.', voice);
  }

  Future<void> saveVoice(Voice voice) async {
    await _voiceBox.put('currentVoice', voice);
  }

  Voice? getSelectedVoice() {
    return _voiceBox.get('currentVoice') as Voice?;
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