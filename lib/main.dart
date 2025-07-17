import 'dart:ui';
import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:hive/hive.dart';
import 'package:hive_flutter/hive_flutter.dart';
import 'package:path_provider/path_provider.dart';

// App imports
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/ui/main_page.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/utils/speech_service_config_adapter.dart';

// Conditionally imported based on platform
import 'package:wingmate/firebase_options.dart';

// Only import Platform when not on web
import 'dart:io' as io show Platform;

// Firebase imports
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';

// Platform detection helpers
bool get isIOS => !kIsWeb && io.Platform.isIOS;
bool get isLinux => !kIsWeb && io.Platform.isLinux;
if (isIOS) {
  final CupertinoThemeData cupertinoTheme = CupertinoThemeData(
  brightness: Brightness.light, // Set the initial brightness
  primaryColor: CupertinoColors.systemBlue,
  scaffoldBackgroundColor: CupertinoColors.systemGroupedBackground,
  barBackgroundColor: CupertinoColors.secondarySystemBackground,
);

final CupertinoThemeData cupertinoDarkTheme = CupertinoThemeData(
  brightness: Brightness.dark,
  primaryColor: CupertinoColors.systemBlue, // CupertinoColors.systemBlue is also adaptive
  scaffoldBackgroundColor: CupertinoColors.black,
  barBackgroundColor: CupertinoColors.systemGrey6,
);

}
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  print('Starting app initialization...');

  try {
    // Initialize core services
    await _initializeServices();

    // Load config and start app
    final config = _loadSpeechServiceConfig();

    runApp(
      MyApp(
        speechServiceEndpoint: config?.endpoint ?? '',
        speechServiceKey: config?.key ?? '',
      ),
    );
  } catch (e, stack) {
    print('Error during app initialization: $e');
    print('Stack trace: $stack');
    runApp(_buildErrorApp(e.toString()));
  }
}

Future<void> _initializeServices() async {
  // Initialize Firebase first on iOS for better startup performance
    await _initializeFirebase();
 
  // Initialize Hive database
  await _initializeHive();
  print('Hive initialized successfully.');

  // Initialize Firebase for other platforms
 

Future<void> _initializeHive() async {
  try {
    if (kIsWeb) {
      await Hive.initFlutter();
    } else {
      final appDocumentDir = await getApplicationDocumentsDirectory();
      Hive.init(appDocumentDir.path);
    }

    Hive.registerAdapter(SpeechServiceConfigAdapter());
    Hive.registerAdapter(VoiceAdapter());

    await Hive.openBox('settings');
    await Hive.openBox('selectedVoice');
  } catch (e) {
    print('Error initializing Hive: $e');
    throw e;
  }
}

Future<void> _initializeFirebase() async {
  if (kIsWeb || isLinux) {
    print('Skipping Firebase initialization on web, Linux or iOS');
    return;
  }

  try {
    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );

    // Configure Crashlytics
    await FirebaseCrashlytics.instance.setCrashlyticsCollectionEnabled(true);

    // Set up error handlers
    FlutterError.onError = FirebaseCrashlytics.instance.recordFlutterFatalError;
    PlatformDispatcher.instance.onError = (error, stack) {
      FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
      return true;
    };

    print('Firebase Crashlytics enabled');
  } catch (e) {
    print('Error initializing Firebase: $e');
    // Continue without Firebase
  }
}

SpeechServiceConfig? _loadSpeechServiceConfig() {
  final box = Hive.box('settings');
  final config = box.get('config') as SpeechServiceConfig?;
  print('Loaded config: $config');
  return config;
}

MaterialApp _buildErrorApp(String errorMessage) {
  return MaterialApp(
    home: Scaffold(
      body: Center(child: Text('Failed to initialize app: $errorMessage')),
    ),
  );
}

class MyApp extends StatefulWidget {
  final String speechServiceEndpoint;
  final String speechServiceKey;

  const MyApp({
    Key? key,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
  }) : super(key: key);

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
    if (kIsWeb || isIOS) {
      return _buildMaterialApp(
        _defaultLightColorScheme,
        _defaultDarkColorScheme,
      );
      else {
      return _buildDynamicColorApp();
    }
  }

  MaterialApp _buildMaterialApp(
    ColorScheme lightScheme,
    ColorScheme darkScheme,
  ) {
    return MaterialApp(
      title: 'Wingmate',
      theme: ThemeData(colorScheme: lightScheme, useMaterial3: true),
      darkTheme: ThemeData(colorScheme: darkScheme, useMaterial3: true),
      themeMode: ThemeMode.system,
      home: _buildMainPage(),
    );
  }

  CupertinoApp _buildCupertinoApp() {
    return CupertinoApp(
      title: 'Wingmate',
      theme: const CupertinoThemeData(primaryColor: CupertinoColors.systemBlue),
      home: _buildMainPage(),
    );
  }

  Widget _buildDynamicColorApp() {
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        final lightColorScheme = lightDynamic ?? _defaultLightColorScheme;
        final darkColorScheme = darkDynamic ?? _defaultDarkColorScheme;
        return _buildMaterialApp(lightColorScheme, darkColorScheme);
      },
    );
  }

  MainPage _buildMainPage() {
    return MainPage(
      speechServiceEndpoint: speechServiceEndpoint,
      speechServiceKey: speechServiceKey,
      onSaveSettings: _saveSettings,
    );
  }

  Future<void> _saveSettings(String endpoint, String key) async {
    final box = Hive.box('settings');
    final config = SpeechServiceConfig(endpoint: endpoint, key: key);
    await box.put('config', config);

    setState(() {
      speechServiceEndpoint = endpoint;
      speechServiceKey = key;
    });
  }

  static final ColorScheme _defaultLightColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.light,
  );

  static final ColorScheme _defaultDarkColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.dark,
  );

}