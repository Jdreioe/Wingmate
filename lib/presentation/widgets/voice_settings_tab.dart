
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/domain/entities/voice.dart';
import 'package:wingmate/infrastructure/services/voice_settings_service.dart';
import 'package:wingmate/infrastructure/config/speech_service_config.dart';
import 'package:wingmate/presentation/widgets/voice_selection_widget.dart';
import 'package:wingmate/presentation/widgets/voice_engine_widget.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

class VoiceSettingsTab extends StatefulWidget {
  final String endpoint;
  final String subscriptionKey;
  final Future<void> Function(String, String, UiSettings) onSaveSettings;
  final UiSettings uiSettings;

  const VoiceSettingsTab({
    Key? key,
    required this.endpoint,
    required this.subscriptionKey,
    required this.onSaveSettings,
    required this.uiSettings,
  }) : super(key: key);

  @override
  _VoiceSettingsTabState createState() => _VoiceSettingsTabState();
}

class _VoiceSettingsTabState extends State<VoiceSettingsTab> {
  late VoiceSettingsService _service;
  late Voice? _selectedVoice;
  late SpeechServiceConfig _config;
  bool _isLoading = true;
  List<Voice> _voices = [];

  @override
  void initState() {
    super.initState();
    _service = VoiceSettingsService(
      voiceBox: Hive.box('selectedVoice'),
      settingsBox: Hive.box('settings'),
      endpoint: widget.endpoint,
      subscriptionKey: widget.subscriptionKey,
      onSaveSettings: (String endpoint, String key) {
        widget.onSaveSettings(endpoint, key, widget.uiSettings);
      },
      context: context,
    );
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    try {
      _selectedVoice = _service.getSelectedVoice();
      _config = _service.getSpeechServiceConfig() ??
          SpeechServiceConfig(
            endpoint: widget.endpoint,
            key: widget.subscriptionKey,
          );
      _voices = await _service.getVoices();
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    return DefaultTabController(
      length: 2,
      child: Column(
        children: [
          const TabBar(
            tabs: [
              Tab(text: 'VOICES'),
              Tab(text: 'VOICE ENGINE'),
            ],
          ),
          Expanded(
            child: TabBarView(
              children: [
                VoiceSelectionWidget(
                  service: _service,
                  selectedVoice: _selectedVoice,
                  voices: _voices,
                ),
                VoiceEngineWidget(
                  service: _service,
                  config: _config,
                  uiSettings: widget.uiSettings,
                  onSaveSettings: widget.onSaveSettings,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
