// Import necessary packages for Flutter, local database, and dynamic theming
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:path_provider/path_provider.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/ui/main_page.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:wingmate/utils/speech_service_config_adapter.dart'; // Ensure this package is added to your pubspec.yaml

void main() async {
  debugPrint('App main started');
  // Ensure Flutter is properly initialized before any async operation
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize local storage with the app's document directory
  final appDocumentDir = await getApplicationDocumentsDirectory();
  Hive.init(appDocumentDir.path);

  // Register Hive adapters for syncing SpeechServiceConfig and Voice data
  Hive.registerAdapter(SpeechServiceConfigAdapter());
  Hive.registerAdapter(VoiceAdapter());

  // Open Hive boxes to store and retrieve settings
  await Hive.openBox('settings');
  await Hive.openBox('selectedVoice');
  
  // Try to retrieve stored configuration from Hive
  final box = Hive.box('settings');
  final config = box.get('config') as SpeechServiceConfig?;
  if (config != null) {
    debugPrint('Config found, launching app');
    final apiKey = config.key;
    final endpoint = config.endpoint;

    if (apiKey.isEmpty || endpoint.isEmpty) {      
      return;
    } else {
    }

    // If config exists and is valid, run the app with the saved settings
    runApp(MyApp(speechServiceEndpoint: endpoint, speechServiceKey: apiKey));
  }
  else 
  {
    // If no config is found, run the app with default settings
    runApp(MyApp(
      speechServiceEndpoint: '',
      speechServiceKey: ' ',
    ));
  }
}

// This widget holds the main MaterialApp and uses dynamic color theming
class MyApp extends StatefulWidget {
  final String speechServiceEndpoint;
  final String speechServiceKey;

  MyApp({
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
  });

  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late String speechServiceEndpoint;
  late String speechServiceKey;

  @override
  void initState() {
    super.initState();
    speechServiceEndpoint = widget.speechServiceEndpoint;
    speechServiceKey = widget.speechServiceKey;
  }

  @override
  Widget build(BuildContext context) {
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        final lightColorScheme = lightDynamic ?? _defaultLightColorScheme;
        final darkColorScheme = darkDynamic ?? _defaultDarkColorScheme;

        return MaterialApp(
          title: 'Wingmate',
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

  static final ColorScheme _defaultLightColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.light,
  );

  static final ColorScheme _defaultDarkColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.dark,
  );

  Future<void> _saveSettings(String endpoint, String key) async {
    final box = Hive.box('settings');
    final config = SpeechServiceConfig(endpoint: endpoint, key: key);
    await box.put('config', config);

    setState(() {
      speechServiceEndpoint = endpoint;
      speechServiceKey = key;
    });
  }
}