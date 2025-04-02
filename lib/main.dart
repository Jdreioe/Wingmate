import 'dart:ui';
import 'dart:async';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/foundation.dart';
import 'package:hive_flutter/hive_flutter.dart';
import 'package:wingmate/utils/speech_service_config_adapter.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/ui/main_page.dart';
import 'package:wingmate/utils/speech_service_config.dart';

// Safely import platform
import 'package:flutter/foundation.dart' show kIsWeb;
// Only import Platform when not on web
import 'dart:io' as io show Platform;

// Firebase imports - keep these conditional
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:path_provider/path_provider.dart';
// Safely check platform
bool get isIOS => !kIsWeb && io.Platform.isIOS;
bool get isLinux => !kIsWeb && io.Platform.isLinux;

void main() async {
  // Catch all errors to prevent crashes
  runZonedGuarded(() async {
    WidgetsFlutterBinding.ensureInitialized();
    
    try {
      // Initialize Hive first - it's usually safer than Firebase
      await _initializeHive();

      // Initialize Firebase only on supported platforms and gracefully handle failures
      await _initializeFirebase();
      
      // Get stored config and run the app
      final box = Hive.box('settings');
      final config = box.get('config') as SpeechServiceConfig?;
      runApp(MyApp(
        speechServiceEndpoint: config?.endpoint ?? '',
        speechServiceKey: config?.key ?? '',
      ));
    } catch (e, stack) {
      print('Error during app initialization: $e');
      print('Stack trace: $stack');
      
      // Show a minimal error app instead of crashing
      runApp(MaterialApp(
        home: Scaffold(
          body: Center(
            child: Text('Failed to initialize app: $e'),
          ),
        ),
      ));
    }
  }, (error, stack) {
    print('Uncaught error: $error');
    print('Stack trace: $stack');
    
    // Report to Firebase if available
    if (!kIsWeb && !isLinux) {
      try {
        FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
      } catch (e) {
        // Ignore Firebase errors
      }
    }
  });
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
    throw e; // Rethrow to handle in the main try-catch
  }
}

Future<void> _initializeFirebase() async {
  if (kIsWeb || isLinux) {
    print('Skipping Firebase initialization on web or Linux');
    return;
  }
  
  try {
    // For iOS, make sure to use the right initialization approach
    if (isIOS) {
      // iOS-specific Firebase initialization
      await Firebase.initializeApp();
      print('Firebase initialized for iOS');
    } else {
      // Standard initialization for other platforms
      await Firebase.initializeApp();
      print('Firebase initialized for Android/other platforms');
    }
    
    // Configure Crashlytics after initialization
    await FirebaseCrashlytics.instance.setCrashlyticsCollectionEnabled(true);
    
    // Set up error handlers
    FlutterError.onError = (errorDetails) {
      FirebaseCrashlytics.instance.recordFlutterFatalError(errorDetails);
    };
    
    PlatformDispatcher.instance.onError = (error, stack) {
      FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
      return true;
    };
    
    print('Firebase Crashlytics enabled');
    
    // Record a test crash for verification (remove for production)
    // FirebaseCrashlytics.instance.log("Firebase Crashlytics initialized");
    
  } catch (e) {
    print('Error initializing Firebase: $e');
    // Don't rethrow - allow app to continue without Firebase
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
    } else if (!kIsWeb && isIOS) {
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