import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/app.dart';

import 'package:wingmate/core/app_initializer.dart';
import 'package:wingmate/config/speech_service_config.dart';

import 'data/app_database.dart';
import 'data/ui_settings.dart';
import 'data/ui_settings_dao.dart';

void main() async {
  try {
    await AppInitializer.initialize();
    final config = _loadSpeechServiceConfig() ??
        SpeechServiceConfig(endpoint: '', key: '');
    final uiSettingsDao = UiSettingsDao(AppDatabase());
    var uiSettings = await uiSettingsDao.getByName('default');
    if (uiSettings == null) {
      uiSettings = UiSettings(name: 'default');
      await uiSettingsDao.insert(uiSettings);
    }

    runApp(
      MyApp(
        speechServiceEndpoint: config.endpoint,
        speechServiceKey: config.key,
        uiSettings: uiSettings,
        onSaveSettings: (endpoint, key, newUiSettings) async {
          final box = Hive.box('settings');
          final config = SpeechServiceConfig(endpoint: endpoint, key: key);
          await box.put('config', config);
          await uiSettingsDao.update(newUiSettings);
        },
      ),
    );
  } catch (e, stack) {
    print('Error during app initialization: $e');
    print('Stack trace: $stack');
    runApp(_buildErrorApp(e.toString()));
  }
}

SpeechServiceConfig? _loadSpeechServiceConfig() {
  final box = Hive.box('settings');
  final config = box.get('config') as SpeechServiceConfig?;
  print('Loaded config (from _loadSpeechServiceConfig): $config');
  return config;
}

MaterialApp _buildErrorApp(String errorMessage) {
  return MaterialApp(
    home: Scaffold(
      body: Center(child: Text('Failed to initialize app: $errorMessage')),
    ),
  );
}
