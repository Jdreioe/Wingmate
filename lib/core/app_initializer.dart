import 'package:flutter/material.dart';
import 'dart:ui';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:flutter/foundation.dart';
import 'package:hive_flutter/hive_flutter.dart';
import 'package:path_provider/path_provider.dart';
import 'package:wingmate/core/platform_info.dart';
import 'package:wingmate/firebase_options.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/config/speech_service_config_adapter.dart';

class AppInitializer {
  static Future<void> initialize() async {
    WidgetsFlutterBinding.ensureInitialized();
    print('Starting app initialization...');

    if (isIOS) {
      await _initializeFirebase();
    }

    await _initializeHive();
    print('Hive initialized successfully.');

    if (!isIOS) {
      await _initializeFirebase();
    }
  }

  static Future<void> _initializeHive() async {
    try {
      if (kIsWeb) {
        await Hive.initFlutter();
      } else {
        final appDocumentDir = await getApplicationDocumentsDirectory();
        Hive.init(appDocumentDir.path);
      }

      if (!Hive.isAdapterRegistered(SpeechServiceConfigAdapter().typeId)) {
        Hive.registerAdapter(SpeechServiceConfigAdapter());
      }
      if (!Hive.isAdapterRegistered(VoiceAdapter().typeId)) {
        Hive.registerAdapter(VoiceAdapter());
      }

      await Hive.openBox('settings');
      await Hive.openBox('selectedVoice');
    } catch (e) {
      print('Error initializing Hive: $e');
      rethrow;
    }
  }

  static Future<void> _initializeFirebase() async {
    if (kDebugMode && (kIsWeb || isLinux)) {
      print('Skipping Firebase initialization for debug on web or Linux.');
      return;
    }

    if (!kIsWeb && !isIOS && !isLinux) {
      try {
        await Firebase.initializeApp(
          options: DefaultFirebaseOptions.currentPlatform,
        );

        if (!kIsWeb && !kDebugMode) {
          await FirebaseCrashlytics.instance
              .setCrashlyticsCollectionEnabled(true);
          FlutterError.onError =
              FirebaseCrashlytics.instance.recordFlutterFatalError;
          PlatformDispatcher.instance.onError = (error, stack) {
            FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
            return true;
          };
          print('Firebase Crashlytics enabled');
        } else {
          print('Firebase Crashlytics skipped (Web/Debug Mode)');
        }
        print('Firebase initialized');
      } catch (e) {
        print('Error initializing Firebase: $e');
      }
    } else if (isIOS) {
      try {
        await Firebase.initializeApp(
          options: DefaultFirebaseOptions.currentPlatform,
        );
        if (!kDebugMode) {
          await FirebaseCrashlytics.instance
              .setCrashlyticsCollectionEnabled(true);
          FlutterError.onError =
              FirebaseCrashlytics.instance.recordFlutterFatalError;
          PlatformDispatcher.instance.onError = (error, stack) {
            FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
            return true;
          };
          print('Firebase Crashlytics enabled on iOS');
        }
        print('Firebase initialized on iOS');
      } catch (e) {
        print('Error initializing Firebase on iOS: $e');
      }
    }
  }
}
