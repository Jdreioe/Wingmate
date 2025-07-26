import 'package:flutter/foundation.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/core/platform_info.dart';
import 'package:wingmate/ui/main_page.dart';
import 'package:wingmate/ui/welcome_page.dart';
import 'package:wingmate/data/ui_settings.dart';

import 'package:wingmate/config/speech_service_config.dart';

class MyApp extends StatefulWidget {
  final String speechServiceEndpoint;
  final String speechServiceKey;
  final UiSettings uiSettings;
  final Future<void> Function(String endpoint, String key, UiSettings uiSettings) onSaveSettings;

  const MyApp({
    Key? key,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
    required this.uiSettings,
    required this.onSaveSettings,
  }) : super(key: key);

  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late String _speechServiceEndpoint;
  late String _speechServiceKey;
  late UiSettings _uiSettings;
  @override
  void initState() {
    super.initState();
    _speechServiceEndpoint = widget.speechServiceEndpoint;
    _speechServiceKey = widget.speechServiceKey;
    _uiSettings = widget.uiSettings;
  }

  bool get _showWelcomeScreen {
    final currentVoice = Hive.box('selectedVoice').get('currentVoice');
    print('Current voice in Hive: $currentVoice');
    return currentVoice == null;
  }

  @override
  Widget build(BuildContext context) {
    if (kIsWeb) {
      return _buildMaterialApp(
        _defaultLightColorScheme,
        _defaultDarkColorScheme,
        _uiSettings.themeMode,
      );
    } else if (isIOS) {
      return _buildCupertinoApp(_uiSettings.themeMode);
    } else {
      return _buildDynamicColorApp(_uiSettings.themeMode);
    }
  }

  Widget _buildMaterialApp(
    ColorScheme lightScheme,
    ColorScheme darkScheme,
    ThemeMode themeMode,
  ) {
    return MaterialApp(
      title: 'Wingmate AAC',
      theme: ThemeData(colorScheme: lightScheme, useMaterial3: true),
      darkTheme: ThemeData(colorScheme: darkScheme, useMaterial3: true),
      themeMode: themeMode,
      home: _showWelcomeScreen
          ? WelcomePage(
              onSettingsSaved: (endpoint, key, uiSettings) {
                setState(() {
                  _speechServiceEndpoint = endpoint;
                  _speechServiceKey = key;
                  _uiSettings = uiSettings;
                });
              },
              uiSettings: _uiSettings,
              onSaveSettings: widget.onSaveSettings,
            )
          : _buildMainPage(),
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [
        Locale('en', ''),
        Locale('es', ''),
        Locale('da', ''),
        Locale('fr', ''),
      ],
    );
  }

  CupertinoApp _buildCupertinoApp(ThemeMode themeMode) {
    return CupertinoApp(
      title: 'Wingmate AAC',
      theme: const CupertinoThemeData(primaryColor: CupertinoColors.systemBlue),
      home: _showWelcomeScreen
          ? WelcomePage(
              onSettingsSaved: (endpoint, key, uiSettings) {
                setState(() {
                  _speechServiceEndpoint = endpoint;
                  _speechServiceKey = key;
                  _uiSettings = uiSettings;
                });
              },
              uiSettings: _uiSettings,
              onSaveSettings: widget.onSaveSettings,
            )
          : _buildMainPage(),
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [
        Locale('en', ''),
        Locale('es', ''),
        Locale('da', ''),
        Locale('fr', ''),
      ],
    );
  }

  Widget _buildDynamicColorApp(ThemeMode themeMode) {
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        final lightColorScheme = lightDynamic ?? _defaultLightColorScheme;
        final darkColorScheme = darkDynamic ?? _defaultDarkColorScheme;
        return _buildMaterialApp(lightColorScheme, darkColorScheme, themeMode);
      },
    );
  }

  MainPage _buildMainPage() {
    final config = _loadSpeechServiceConfig();
    final speechServiceEndpoint = config?.endpoint ?? '';
    final speechServiceKey = config?.key ?? '';
    return MainPage(
      speechServiceEndpoint: _speechServiceEndpoint,
      speechServiceKey: _speechServiceKey,
      onSaveSettings: _saveSettings, uiSettings: _uiSettings,
    );
  }

  SpeechServiceConfig? _loadSpeechServiceConfig() {
    final box = Hive.box('settings');
    final config = box.get('config') as SpeechServiceConfig?;
    print('Loaded config (from _loadSpeechServiceConfig): $config');
    return config;
  }

  Future<void> _saveSettings(String endpoint, String key, UiSettings newUiSettings) async {
    final box = Hive.box('settings');
    final config = SpeechServiceConfig(endpoint: endpoint, key: key);
    await box.put('config', config);

    setState(() {
      _speechServiceEndpoint = endpoint;
                  _speechServiceKey = key;
      _uiSettings = newUiSettings;
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
