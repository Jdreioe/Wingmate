import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:wingmate/presentation/bloc/main_page_bloc.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/core/platform_info.dart';
import 'package:wingmate/presentation/pages/main_page.dart';
import 'package:wingmate/presentation/pages/welcome_page.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

import 'package:wingmate/infrastructure/config/speech_service_config.dart';

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
  @override
  void initState() {
    super.initState();
  }

  bool get _showWelcomeScreen {
    final currentVoice = Hive.box('selectedVoice').get('currentVoice');
    print('Current voice in Hive: $currentVoice');
    return currentVoice == null;
  }

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<MainPageBloc, MainPageState>(
      builder: (context, state) {
        if (state is MainPageLoaded) {
          if (kIsWeb) {
            return _buildMaterialApp(
              _defaultLightColorScheme,
              _defaultDarkColorScheme,
              state.uiSettings.themeMode,
            );
          } else if (isIOS) {
            return _buildCupertinoApp(state.uiSettings.themeMode);
          } else {
            return _buildDynamicColorApp(state.uiSettings.themeMode);
          }
        }
        return const SizedBox.shrink(); // Or a loading indicator
      },
    );
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
                context.read<MainPageBloc>().add(UpdateUiSettings(uiSettings));
              },
              uiSettings: widget.uiSettings,
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
                context.read<MainPageBloc>().add(UpdateUiSettings(uiSettings));
              },
              uiSettings: widget.uiSettings,
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

  Widget _buildMainPage() {
    return BlocBuilder<MainPageBloc, MainPageState>(
      builder: (context, state) {
        if (state is MainPageLoaded) {
          return MainPage(
            speechServiceEndpoint: widget.speechServiceEndpoint,
            speechServiceKey: widget.speechServiceKey,
            onSaveSettings: widget.onSaveSettings,
            uiSettings: state.uiSettings,
          );
        }
        return const SizedBox.shrink(); // Or a loading indicator
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
}
