import 'dart:ui';
import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'; // ADDED: Required for DefaultMaterialLocalizations.delegate
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

final CupertinoThemeData cupertinoTheme = CupertinoThemeData(
  brightness: Brightness.light,
  primaryColor: CupertinoColors.systemBlue,
  scaffoldBackgroundColor: CupertinoColors.systemGroupedBackground,
  barBackgroundColor: CupertinoColors.secondarySystemBackground,
);

final CupertinoThemeData cupertinoDarkTheme = CupertinoThemeData(
  brightness: Brightness.dark,
  primaryColor: CupertinoColors.systemBlue,
  scaffoldBackgroundColor: CupertinoColors.black,
  barBackgroundColor: CupertinoColors.systemGrey6,
);

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  print('Starting app initialization...');

  try {
    await _initializeServices();
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
  await _initializeFirebase();
  await _initializeHive();
  print('Hive initialized successfully.');
}

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
    rethrow;
  }
}

Future<void> _initializeFirebase() async {
  if (kIsWeb || isLinux) {
    print('Skipping Firebase initialization on web or Linux');
    return;
  }
  try {
    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );
    await FirebaseCrashlytics.instance.setCrashlyticsCollectionEnabled(true);
    FlutterError.onError = FirebaseCrashlytics.instance.recordFlutterFatalError;
    PlatformDispatcher.instance.onError = (error, stack) {
      FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
      return true;
    };
    print('Firebase Crashlytics enabled');
  } catch (e) {
    print('Error initializing Firebase: $e');
  }
}

SpeechServiceConfig? _loadSpeechServiceConfig() {
  final box = Hive.box('settings');
  final config = box.get('config') as SpeechServiceConfig?;
  print('Loaded config: $config');
  return config;
}

CupertinoApp _buildErrorApp(String errorMessage) {
  return CupertinoApp(
    home: CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Error'),
      ),
      child: Center(
        child: Text(
          'Failed to initialize app: $errorMessage',
          textAlign: TextAlign.center,
        ),
      ),
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
    return CupertinoApp(
      title: 'Wingmate',
      theme: cupertinoTheme,
      home: _buildMainPage(),
      localizationsDelegates: const [
        // Added back the material delegate
        DefaultMaterialLocalizations.delegate,
        DefaultCupertinoLocalizations.delegate,
        DefaultWidgetsLocalizations.delegate,
      ],
      supportedLocales: const [
        Locale('en', 'US'),
      ],
    );
  }

  CupertinoPageScaffold _buildMainPage() {
    return CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Wingmate'),
      ),
      child: MainPage(
        speechServiceEndpoint: speechServiceEndpoint,
        speechServiceKey: speechServiceKey,
        onSaveSettings: _saveSettings,
      ),
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
}