import 'package:flutter/material.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:hive/hive.dart';
import 'package:path_provider/path_provider.dart';
import 'package:wingmancrossplatform/utils/speech_service_config_adapter.dart';
import 'utils/speech_service_config.dart';
import 'screens/main_page.dart';

final _defaultLightColorScheme =
    ColorScheme.fromSwatch(primarySwatch: Colors.red);
final _defaultDarkColorScheme = ColorScheme.fromSwatch(
  primarySwatch: Colors.red,
  brightness: Brightness.dark,
);

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize Hive
  final appDocumentDirectory = await getApplicationDocumentsDirectory();
  Hive.init(appDocumentDirectory.path);
  Hive.registerAdapter(SpeechServiceConfigAdapter());
  runApp(MainApp());
}

class MainApp extends StatefulWidget {
  @override
  _MainAppState createState() => _MainAppState();
}

class _MainAppState extends State<MainApp> {
  late String _speechServiceEndpoint = '';
  late String _speechServiceKey = '';

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final box = await Hive.openBox('settings');
    final config = box.get('config');
    if (config != null && config is SpeechServiceConfig) {
      setState(() {
        _speechServiceEndpoint = config.endpoint;
        _speechServiceKey = config.key;
      });
    }
  }

  Future<void> _saveSettings(String endpoint, String key) async {
    final box = await Hive.openBox('settings');
    final config = SpeechServiceConfig(endpoint: endpoint, key: key);
    await box.put('config', config);
    setState(() {
      _speechServiceEndpoint = endpoint;
      _speechServiceKey = key;
    });
  }

  @override
  Widget build(BuildContext context) {
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        final lightColorScheme = lightDynamic ?? _defaultLightColorScheme;
        final darkColorScheme = darkDynamic ?? _defaultDarkColorScheme;

        return MaterialApp(
          title: 'Wingman',
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
            speechServiceEndpoint: _speechServiceEndpoint,
            speechServiceKey: _speechServiceKey,
            onSaveSettings: _saveSettings,
          ),
        );
      },
    );
  }
}
