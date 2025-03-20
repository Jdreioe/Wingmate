import 'dart:ui';

import 'package:dynamic_color/dynamic_color.dart';
import 'package:wingmate/utils/speech_service_config_adapter.dart'; // Ensure this package is added to your pubspec.yaml
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'dart:io'; // Add this import
import 'package:wingmate/ui/speech_to_text_page.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:hive/hive.dart';
import 'package:path_provider/path_provider.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/ui/main_page.dart';
import 'package:wingmate/utils/speech_service_config.dart';

void main() async {
  // Ensure Flutter is properly initialized before any async operation
  WidgetsFlutterBinding.ensureInitialized();
  
  if (!Platform.isLinux) { // Check if not running on Linux
    await Firebase.initializeApp();
    FlutterError.onError = (errorDetails) {
      FirebaseCrashlytics.instance.recordFlutterFatalError(errorDetails);
    };
    // Pass all uncaught asynchronous errors that aren't handled by the Flutter framework to Crashlytics
    PlatformDispatcher.instance.onError = (error, stack) {
      FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
      return true;
    };
  }
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
    final apiKey = config.key;
    final endpoint = config.endpoint;

    // If config exists and is valid, run the app with the saved settings
    runApp(MyApp(speechServiceEndpoint: endpoint, speechServiceKey: apiKey));
  } else {
    // If no config is found, run the app with default settings
    runApp(MyApp(
      speechServiceEndpoint: '',
      speechServiceKey: ' ',
    ));
  }
}

// This widget holds the main CupertinoApp or MaterialApp based on the platform
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
    if (Platform.isIOS) {
      return CupertinoApp(
        title: 'Wingmate',
        theme: CupertinoThemeData(
          primaryColor: CupertinoColors.systemBlue,
        ),
        localizationsDelegates: [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: [
          const Locale('en', ''), // English
          const Locale('da'), // Danish
        ],
        routes: {
          '/': (context) => MainPage(
            speechServiceEndpoint: speechServiceEndpoint,
            speechServiceKey: speechServiceKey,
            onSaveSettings: _saveSettings,
          ),
          '/speech_to_text': (context) => SpeechToTextPage(),
        },
      );
    } else {
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
            localizationsDelegates: [
              AppLocalizations.delegate,
              GlobalMaterialLocalizations.delegate,
              GlobalWidgetsLocalizations.delegate,
              GlobalCupertinoLocalizations.delegate,
            ],
            supportedLocales: [
              const Locale('en', ''), // English
              const Locale('da'), // Danish
            ],
            routes: {
              '/': (context) => MainPage(
                speechServiceEndpoint: speechServiceEndpoint,
                speechServiceKey: speechServiceKey,
                onSaveSettings: _saveSettings,
              ),
              '/speech_to_text': (context) => SpeechToTextPage(),
            },
          );
        },
      );
    }
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