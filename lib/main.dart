import 'dart:ui';

import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/foundation.dart';
import 'package:hive_flutter/hive_flutter.dart';
import 'package:wingmate/utils/speech_service_config_adapter.dart'; // Ensure this package is added to your pubspec.yaml
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'dart:io' show Platform; // Add this import
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
  WidgetsFlutterBinding.ensureInitialized();

  try {
    if (!kIsWeb && !Platform.isLinux) {
      // Initialize Firebase only for non-web platforms
      await Firebase.initializeApp();
      FlutterError.onError = (errorDetails) {
        FirebaseCrashlytics.instance.recordFlutterFatalError(errorDetails);
      };
      PlatformDispatcher.instance.onError = (error, stack) {
        FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
        return true;
      };
    }

    if (kIsWeb) {
      // Initialize Hive for web
      await Hive.initFlutter();
    } else {
      // Initialize Hive for mobile and desktop
      final appDocumentDir = await getApplicationDocumentsDirectory();
      Hive.init(appDocumentDir.path);
    }

    Hive.registerAdapter(SpeechServiceConfigAdapter());
    Hive.registerAdapter(VoiceAdapter());

    await Hive.openBox('settings');
    await Hive.openBox('selectedVoice');
  } catch (e) {
    print('Error initializing Hive: $e');
    return; // Exit if Hive initialization fails
  }

  final box = Hive.box('settings');
  final config = box.get('config') as SpeechServiceConfig?;
  runApp(MyApp(
    speechServiceEndpoint: config?.endpoint ?? '',
    speechServiceKey: config?.key ?? '',
  ));
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
    if (kIsWeb) {
      // Use MaterialApp for web
      return MaterialApp(
        title: 'Wingmate',
        theme: ThemeData(
          colorScheme: _defaultLightColorScheme,
          useMaterial3: true,
        ),
        darkTheme: ThemeData(
          colorScheme: _defaultDarkColorScheme,
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
        home: MainPage(
          speechServiceEndpoint: speechServiceEndpoint,
          speechServiceKey: speechServiceKey,
          onSaveSettings: _saveSettings,
        ),
      );
    } else if (!kIsWeb && Platform.isIOS) {
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
        home: MainPage(
          speechServiceEndpoint: speechServiceEndpoint,
          speechServiceKey: speechServiceKey,
          onSaveSettings: _saveSettings,
        ),
      );
    } else {
      // For Android, Linux, Windows, macOS
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
            home: MainPage(
              speechServiceEndpoint: speechServiceEndpoint,
              speechServiceKey: speechServiceKey,
              onSaveSettings: _saveSettings,
            ),
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