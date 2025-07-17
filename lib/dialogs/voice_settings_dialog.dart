import 'dart:io'
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/voice_settings_service.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/widgets/voice_selection_widget.dart';
import 'package:wingmate/widgets/settings_widget.dart';

Future<void> showVoiceSettingsDialog(
  BuildContext context,
  String endpoint,
  String subscriptionKey,
  Function(String, String) onSaveSettings,
) async {
  final voiceBox = Hive.box('selectedVoice');
  final settingsBox = Hive.box('settings');

  final service = VoiceSettingsService(
    voiceBox: voiceBox,
    settingsBox: settingsBox,
    endpoint: endpoint,
    subscriptionKey: subscriptionKey,
    onSaveSettings: onSaveSettings,
    context: context,
  );

  // Consolidated to use a single showDialog for all platforms
  await showDialog(
    context: context,
    builder: (context) => VoiceSettingsDialog(
      service: service,
    ),
  );
}

class VoiceSettingsDialog extends StatefulWidget {
  final VoiceSettingsService service;

  // Removed the isCupertino parameter
  const VoiceSettingsDialog({
    Key? key,
    required this.service,
  }) : super(key: key);

  @override
  _VoiceSettingsDialogState createState() => _VoiceSettingsDialogState();
}

class _VoiceSettingsDialogState extends State<VoiceSettingsDialog> {
  // Removed _voices as it's not used here
  late Voice? _selectedVoice;
  late SpeechServiceConfig _config;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    try {
      _selectedVoice = widget.service.getSelectedVoice();
      _config = widget.service.getSpeechServiceConfig() ??
          SpeechServiceConfig(
            endpoint: widget.service.endpoint,
            key: widget.service.subscriptionKey,
          );
    } finally {
      setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    // Consolidated to a single Material Dialog for all platforms
    return Dialog(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AppBar(
            title: const Text('Voice Settings'),
            leading: IconButton(
              icon: const Icon(Icons.close),
              onPressed: () => Navigator.pop(context),
            ),
          ),
          Expanded(child: _buildContent()),
        ],
      ),
    );
  }

  Widget _buildContent() {
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
              Tab(text: 'SETTINGS'),
            ],
          ),
          Expanded(
            child: TabBarView(
              children: [
                VoiceSelectionWidget(
                  service: widget.service,
                  selectedVoice: _selectedVoice,
                ),
                SettingsWidget(
                  service: widget.service,
                  config: _config,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}