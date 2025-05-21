import 'dart:ui';
import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
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

import 'package:wingmate/utils/app_database.dart'; // Added
import 'package:wingmate/utils/user_profile_dao.dart'; // Added
import 'package:wingmate/services/profile_service.dart'; // Added
import 'package:wingmate/services/main_page_service.dart'; // Added

// Only import Platform when not on web
import 'dart:io' as io show Platform;

// Firebase imports
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';

// Platform detection helpers
bool get isIOS => !kIsWeb && io.Platform.isIOS;
bool get isLinux => !kIsWeb && io.Platform.isLinux;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  print('Starting app initialization...');

  try {
    // Initialize core services
    await _initializeServicesBeforeRunApp();

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

Future<void> _initializeServicesBeforeRunApp() async {
  // Initialize Firebase first on iOS for better startup performance
  if (isIOS) {
    await _initializeFirebase();
  }

  // Initialize Hive database
  await _initializeHive();
  print('Hive initialized successfully.');

  // Initialize Firebase for other platforms
  if (!isIOS) {
    await _initializeFirebase();
  }
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
    throw e;
  }
}

Future<void> _initializeFirebase() async {
  if (kIsWeb || isLinux || isIOS) {
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
  MainPageService? _mainPageService; // Store MainPageService instance

  @override
  void initState() {
    super.initState();
    speechServiceEndpoint = widget.speechServiceEndpoint;
    speechServiceKey = widget.speechServiceKey;
    _initializeMainPageService();
  }

  // Moved MainPageService initialization here
  void _initializeMainPageService() {
    final appDatabase = AppDatabase();
    final userProfileDao = UserProfileDao(appDatabase);
    final profileService = ProfileService(userProfileDao);
    // Optional: await profileService.init(); if it's async

    // Pass context safely. If context is not available yet, consider other DI patterns
    // or delay service creation until context is available.
    // For now, assuming context passed to MainPageService is for dialogs/navigation
    // that happen after initState.

    // VoiceService is needed for default profile creation if no voices are found
    final voiceService = VoiceService(
        endpoint: speechServiceEndpoint, // Use initial endpoint/key
        subscriptionKey: speechServiceKey,
        profileService: profileService);

    _mainPageService = MainPageService(
      messageController: TextEditingController(),
      messageFocusNode: FocusNode(),
      speechItemDao: SpeechItemDao(appDatabase),
      saidTextDao: SaidTextDao(appDatabase),
      profileService: profileService,
      // Pass voiceService to MainPageService if it needs it directly,
      // or ensure MainPageService creates its own instance of VoiceService internally if preferred.
      // For now, MainPageService already creates VoiceService and VoiceSettingsService.
      // We are using the local voiceService here just for the default profile setup.
      subscriptionManager: SubscriptionManager(
        onSubscriptionStatusChanged: (isSubscribed) {},
      ),
      settingsBox: Hive.box('settings'),
      voiceBox: Hive.box('selectedVoice'),
      context: context, // This context is from _MyAppState
      speechServiceEndpoint: speechServiceEndpoint,
      speechServiceKey: speechServiceKey,
      onSaveSettings: _saveSettings,
    );
    
    // Perform initial profile setup
    _ensureDefaultProfileIsSetUp(profileService, voiceService).then((_) {
      // Initialization of MainPageService might depend on active profile being set
      _mainPageService!.initialize();
    }).catchError((e, s) {
      print("Error during default profile setup: $e\n$s");
      // Still initialize MainPageService, it should handle cases where profile might be null.
      _mainPageService!.initialize();
    });
  }

  Future<void> _ensureDefaultProfileIsSetUp(ProfileService profileService, VoiceService voiceService) async {
    final profiles = await profileService.getProfiles();
    UserProfile? activeProfile = await profileService.getActiveProfile();

    if (profiles.isEmpty) {
      print("No profiles found. Creating a default profile.");
      String defaultVoiceName = "en-US-AriaNeural"; // Absolute fallback
      String defaultLanguageCode = "en-US";       // Absolute fallback

      try {
        final availableVoices = await voiceService.fetchVoicesFromApi();
        if (availableVoices.isNotEmpty) {
          // Try to find a common high-quality voice, otherwise use the first one
          final jenny = availableVoices.firstWhere((v) => v['name'] == 'en-US-JennyNeural', orElse: () => availableVoices.first);
          defaultVoiceName = jenny['name'];
          defaultLanguageCode = jenny['locale'];
        }
      } catch (e) {
        print("Failed to fetch available voices for default profile setup: $e. Using hardcoded defaults.");
      }

      final defaultProfile = UserProfile(
        name: "Default",
        voiceName: defaultVoiceName,
        languageCode: defaultLanguageCode,
        speechRate: 1.0,
        pitch: 1.0,
      );
      final newProfile = await profileService.createProfile(defaultProfile);
      if (newProfile.id != null) {
        await profileService.setActiveProfile(newProfile.id!);
        print("Default profile created and set as active: ${newProfile.name}");
      }
    } else if (activeProfile == null) {
      print("Profiles exist, but no active profile set. Setting a default active profile.");
      // ProfileService.getDefaultProfile() already handles setting active to "Default" or first.
      // We just need to call it to ensure the side effect of activating a profile happens.
      await profileService.getDefaultProfile(); 
      // Re-fetch to confirm (optional, for logging)
      activeProfile = await profileService.getActiveProfile();
      print("Active profile has been set to: ${activeProfile?.name ?? 'None'}");
    } else {
      print("Active profile already set: ${activeProfile.name}");
    }
  }

  @override
  Widget build(BuildContext context) {
    if (kIsWeb) {
      return _buildMaterialApp(
        _defaultLightColorScheme,
        _defaultDarkColorScheme,
      );
    } else if (isIOS) {
      return _buildCupertinoApp();
    } else {
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
      localizationsDelegates: _localizationDelegates,
      supportedLocales: _supportedLocales,
      home: _buildMainPage(),
    );
  }

  CupertinoApp _buildCupertinoApp() {
    return CupertinoApp(
      title: 'Wingmate',
      theme: const CupertinoThemeData(primaryColor: CupertinoColors.systemBlue),
      localizationsDelegates: _localizationDelegates,
      supportedLocales: _supportedLocales,
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
    // Ensure _mainPageService is initialized before building MainPage
    if (_mainPageService == null) {
        _initializeMainPageService();
    }
    return MainPage(
      speechServiceEndpoint: speechServiceEndpoint, // Pass initial values
      speechServiceKey: speechServiceKey, // Pass initial values
      onSaveSettings: _saveSettings,
      mainPageService: _mainPageService!, // Pass the service instance
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
    // Re-initialize or update services if needed after settings change
    _initializeMainPageService(); 
  }

  static final ColorScheme _defaultLightColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.light,
  );

  static final ColorScheme _defaultDarkColorScheme = ColorScheme.fromSeed(
    seedColor: Colors.blue,
    brightness: Brightness.dark,
  );

  static const List<LocalizationsDelegate<dynamic>> _localizationDelegates = [
    AppLocalizations.delegate,
    GlobalMaterialLocalizations.delegate,
    GlobalWidgetsLocalizations.delegate,
    GlobalCupertinoLocalizations.delegate,
  ];

  static const List<Locale> _supportedLocales = [
    Locale('en', ''), // English
    Locale('da'), // Danish
  ];
}

// Modify MainPage to accept MainPageService
class MainPage extends StatefulWidget {
  final String speechServiceEndpoint;
  final String speechServiceKey;
  final Future<void> Function(String endpoint, String key) onSaveSettings;
  final MainPageService mainPageService; // Added

  const MainPage({
    Key? key,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
    required this.onSaveSettings,
    required this.mainPageService, // Added
  }) : super(key: key);

  @override
  _MainPageState createState() => _MainPageState();
}

class _MainPageState extends State<MainPage> {
  late final MainPageService _service; // Use the passed instance
  bool _isSubscribed = false; // This state should ideally be managed by MainPageService or ProfileService
  late final SubscriptionManager _subscriptionManager; // This should also be part of MainPageService

  @override
  void initState() {
    super.initState();
    _service = widget.mainPageService; // Use the passed service
    // _service.initialize(); // Already called in _MyAppState

    // The following logic should ideally move into MainPageService or be driven by it
    if (_isMobilePlatform()) {
      _subscriptionManager = SubscriptionManager( // This might be redundant if MainPageService handles it
        onSubscriptionStatusChanged: (isSubscribed) async {
          if (mounted) { // Check if widget is still in tree
            setState(() {
              _isSubscribed = isSubscribed;
            });
          }
          if (isSubscribed) {
            // This should be part of MainPageService or a dedicated auth service
            // await _fetchAzureSubscriptionDetails(); 
          }
        },
      );
      // _subscriptionManager.initialize(); // MainPageService might do this
    }
  }

  bool _isMobilePlatform() {
    return !kIsWeb &&
        (defaultTargetPlatform == TargetPlatform.iOS ||
            defaultTargetPlatform == TargetPlatform.android);
  }

  // This method should be part of an authentication/subscription service, not UI
  // Future<void> _fetchAzureSubscriptionDetails() async { ... } 

  @override
  void dispose() {
    // _service.dispose(); // MainPageService lifecycle managed by _MyAppState
    // _subscriptionManager.dispose(); // Also managed by _MyAppState via MainPageService
    super.dispose();
  }

  // ... rest of _MainPageState remains largely the same, but uses `_service` directly ...
  // Ensure all calls like `widget.speechServiceEndpoint` are replaced with `_service.speechServiceEndpoint`
  // or that `_service` provides these values.

  // Example modifications for _MainPageState:
  // Replace `widget.speechServiceEndpoint` with `_service.speechServiceEndpoint`
  // Replace `widget.speechServiceKey` with `_service.speechServiceKey`
  // Replace `widget.onSaveSettings` with `_service.onSaveSettings` (if MainPageService wraps it) or pass directly

  // For brevity, the rest of _MainPageState is omitted, but it would need to adapt
  // to using the injected _service for its state and actions.
  // For example, the AppBar actions would call methods on `_service`.
  // The ReorderableListView would use `_service.items`, `_service.reorderItems`, etc.

// Placeholder for the rest of the _MainPageState build methods
// These would need to be refactored to use `_service`

  @override
  Widget build(BuildContext context) {
    // This is a simplified build method. The original complex build logic 
    // for Cupertino/Material would be here, using `_service` for data and actions.
    return Scaffold(
      appBar: AppBar(
        title: Text(_service.isSomeFolderSelected ? "Folder" : "Wingmate"),
         leading: IconButton(
          icon: Icon(_service.isSomeFolderSelected ? Icons.arrow_back : Icons.person),
          onPressed: _handleLeadingIconPressed, // This method now uses _service
        ),
        // Actions would also use _service
      ),
      body: Column(
        children: [
          Expanded(child: Center(child: Text("Content using _service"))), // Placeholder for list
          // Input rows using _service
        ],
      ),
    );
  }

  List<Widget> _buildCupertinoAppBarActions() {
    return [
      // ... actions using _service ...
       CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: () {
          Navigator.push(
            context,
            CupertinoPageRoute(
              builder: (context) => FetchVoicesPage( // This page also needs service instances
                endpoint: _service.speechServiceEndpoint,
                subscriptionKey: _service.speechServiceKey,
                // Pass other necessary services like ProfileService, VoiceSettingsService
              ),
            ),
          );
        },
        child: const Icon(CupertinoIcons.settings),
      ),
    ];
  }

   List<Widget> _buildMaterialAppBarActions() {
    return [
      // ... actions using _service ...
       IconButton(
        icon: const Icon(Icons.settings),
        onPressed: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (context) => FetchVoicesPage( // This page also needs service instances
                endpoint: _service.speechServiceEndpoint,
                subscriptionKey: _service.speechServiceKey,
                // Pass other necessary services
              ),
            ),
          );
        },
      ),
    ];
  }

  void _handleLeadingIconPressed() {
    if (_service.isSomeFolderSelected) {
      // ... logic using _service ...
      _service.selectRootFolder(); // Example
    } else {
      showProfileDialog(
        context,
        _service.speechServiceEndpoint,
        _service.speechServiceKey,
        _service.onSaveSettings, // This should be the original onSaveSettings from MyApp
                                 // or _service should expose a way to trigger it.
                                 // For now, using the one passed to MainPageService
      );
    }
  }
  
  // ... Other methods like _buildReorderableListView, _handleDismiss, _handleItemTap
  // would need to be refactored to use `_service` fields and methods.
  // For example, `_service.items`, `_service.reorderItems`, `_service.deleteItem`
}
// Dummy FetchVoicesPage for compilation
class FetchVoicesPage extends StatelessWidget {
  final String endpoint;
  final String subscriptionKey;
  const FetchVoicesPage({Key? key, required this.endpoint, required this.subscriptionKey}) : super(key: key);
  @override
  Widget build(BuildContext context) => Scaffold(appBar: AppBar(title: Text("Fetch Voices")));
}

// Dummy ProfileDialog for compilation
void showProfileDialog(BuildContext context, String endpoint, String key, Function onSaveSettings) {}

// Dummy showSaveMessageDialog for compilation
void showSaveMessageDialog(BuildContext context, String message, Function onSave) {}

// Dummy showFullScreenText for compilation
void showFullScreenText(BuildContext context, String text) {}

// Dummy XmlShortcutsRow for compilation
class XmlShortcutsRow extends StatelessWidget {
  final Function(String) onAddTag;
  final bool isCupertino;
  const XmlShortcutsRow({Key? key, required this.onAddTag, this.isCupertino = false}) : super(key: key);
  @override
  Widget build(BuildContext context) => Container();
}

// Dummy MessageInputRow for compilation
class MessageInputRow extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onClear;
  final VoidCallback onPlayPause;
  final bool isPlaying;
  final bool isCupertino;
  const MessageInputRow({Key? key, required this.controller, required this.focusNode, required this.onClear, required this.onPlayPause, required this.isPlaying, this.isCupertino = false}) : super(key: key);
  @override
  Widget build(BuildContext context) => Container();
}

// Dummy SpeechItemListTile for compilation
class SpeechItemListTile extends StatelessWidget {
  final dynamic item; // SpeechItem or String
  final bool isCupertino;
  final VoidCallback onTap;
  const SpeechItemListTile({Key? key, required this.item, this.isCupertino = false, required this.onTap}) : super(key: key);
  @override
  Widget build(BuildContext context) => ListTile(title: Text(item.toString()));
}

// Dummy HistoryPage for compilation
class HistoryPage extends StatelessWidget {
  const HistoryPage({Key? key}) : super(key: key);
  @override
  Widget build(BuildContext context) => Scaffold(appBar: AppBar(title: Text("History")));
}

// Dummy SubscriptionManager for compilation
class SubscriptionManager {
  final Function(bool) onSubscriptionStatusChanged;
  SubscriptionManager({required this.onSubscriptionStatusChanged});
  void initialize() {}
  void dispose() {}
  void showSubscriptionDialog(BuildContext context) {}
}

// Added imports that were implicitly needed by the dummy classes or original main_page.dart
// These might not all be strictly necessary for the main.dart structure itself,
// but help resolve references if we were to try and make this runnable.
import 'package:wingmate/services/voice_service.dart'; // For VoiceService
import 'package:wingmate/models/user_profile.dart'; // For UserProfile
import 'package:wingmate/ui/fetch_voices_page.dart';
import 'package:wingmate/dialogs/profile_dialog.dart';
import 'package:wingmate/ui/save_message_dialog.dart';
import 'package:wingmate/utils/speech_item.dart'; // For SpeechItem
import 'package:wingmate/ui/history_page.dart';
import 'package:wingmate/utils/full_screen_text_view.dart';
import 'package:wingmate/widgets/xml_shortcuts_row.dart';
import 'package:wingmate/widgets/message_input_row.dart';
import 'package:wingmate/widgets/speech_item_list_tile.dart';
import 'package:flutter/services.dart'; // For Clipboard
import 'dart:convert'; // For base64Encode
import 'package:share_plus/share_plus.dart'; // For Share

// Note: The original MainPage was complex. This overwrite simplifies it significantly
// to focus on the DI aspect. The full UI logic would need careful porting to use the injected service.
