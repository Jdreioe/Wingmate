import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:path_provider/path_provider.dart';
import 'package:wingmancrossplatform/models/voice_model.dart';
import 'package:wingmancrossplatform/screens/main_page.dart';
import 'package:wingmancrossplatform/utils/speech_service_config.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:wingmancrossplatform/utils/speech_service_config_adapter.dart'; // Ensure this package is added to your pubspec.yaml

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final appDocumentDir = await getApplicationDocumentsDirectory();
  Hive.init(appDocumentDir.path);

  // Register the adapter
  Hive.registerAdapter(SpeechServiceConfigAdapter());
  Hive.registerAdapter(VoiceAdapter());

  // Open the settings box
  await Hive.openBox('settings');
  await Hive.openBox('selectedVoice');
  // Retrieve the API key and region from Hive
  final box = Hive.box('settings');
  final config = box.get('config') as SpeechServiceConfig?;
  if (config != null) {
    final apiKey = config.key;
    final endpoint = config.endpoint;

    if (apiKey.isEmpty || endpoint.isEmpty) {
      debugPrint('API key or endpoint not found in Hive box');
      return;
    }

    runApp(MyApp(speechServiceEndpoint: endpoint, speechServiceKey: apiKey));
  } else {
    debugPrint(
        'TtsMicrosoft initialization failed: API key or endpoint not found.');
  }
}

class MyApp extends StatelessWidget {
  final String speechServiceEndpoint;
  final String speechServiceKey;

  MyApp({
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
  });

  @override
  Widget build(BuildContext context) {
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        final lightColorScheme = lightDynamic ?? _defaultLightColorScheme;
        final darkColorScheme = darkDynamic ?? _defaultDarkColorScheme;

        return MaterialApp(
          title: 'Wingman',
          theme: ThemeData(
            colorScheme: lightColorScheme,
            useMaterial3: true,
          ),
          darkTheme: ThemeData(
            colorScheme: darkColorScheme,
            useMaterial3: true,
          ),
          themeMode: ThemeMode.system,
          home: MainPage(
            speechServiceEndpoint: speechServiceEndpoint,
            speechServiceKey: speechServiceKey,
            onSaveSettings: _saveSettings,
          ),
        );
      },
    );
  }

  // Define default light and dark color schemes
  static final ColorScheme _defaultLightColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.light,
  );

  static final ColorScheme _defaultDarkColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.dark,
  );

  // Save settings function
  Future<void> _saveSettings(String endpoint, String key) async {
    final box = Hive.box('settings');
    final config = SpeechServiceConfig(endpoint: endpoint, key: key);
    await box.put('config', config);
  }
}
