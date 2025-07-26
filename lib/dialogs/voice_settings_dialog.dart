import 'dart:io';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/voice_settings_service.dart';
import 'package:wingmate/config/speech_service_config.dart';
import 'package:wingmate/widgets/voice_selection_widget.dart';
import 'package:wingmate/widgets/settings_widget.dart';

Future<void> showVoiceSettingsDialog(
  BuildContext context,
  String endpoint,
  String subscriptionKey,
  Function(String, String) onSaveSettings,
) async {
  final isCupertino = Platform.isIOS;
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

  if (isCupertino) {
    await showCupertinoModalPopup(
      context: context,
      builder: (context) => VoiceSettingsDialog(
        service: service,
        isCupertino: true,
      ),
    );
  } else {
    await showDialog(
      context: context,
      builder: (context) => VoiceSettingsDialog(
        service: service,
        isCupertino: false,
      ),
    );
  }
}

class VoiceSettingsDialog extends StatefulWidget {
  final VoiceSettingsService service;
  final bool isCupertino;

  const VoiceSettingsDialog({
    Key? key,
    required this.service,
    required this.isCupertino,
  }) : super(key: key);

  @override
  _VoiceSettingsDialogState createState() => _VoiceSettingsDialogState();
}

class _VoiceSettingsDialogState extends State<VoiceSettingsDialog> {
  late List<Voice> _voices;
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
    if (widget.isCupertino) {
      return CupertinoPageScaffold(
        navigationBar: CupertinoNavigationBar(
          middle: const Text('Voice Settings'),
          trailing: CupertinoButton(
            padding: EdgeInsets.zero,
            onPressed: () => Navigator.pop(context),
            child: const Text('Done'),
          ),
        ),
        child: _buildContent(),
      );
    } else {
      return Dialog(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AppBar(
              title: const Text('Voice Settings'),
              actions: [
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.pop(context),
                ),
              ],
            ),
            _buildContent(),
          ],
        ),
      );
    }
  }

  Widget _buildContent() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    return DefaultTabController(
      length: 2,
      child: Column(
        children: [
          TabBar(
            tabs: [
              Tab(text: widget.isCupertino ? 'Voices' : 'VOICES'),
              Tab(text: widget.isCupertino ? 'Settings' : 'SETTINGS'),
            ],
          ),
          Expanded(
            child: TabBarView(
              children: [
                VoiceSelectionWidget(
                  service: widget.service,
                  isCupertino: widget.isCupertino,
                  voices: _voices,
                  selectedVoice: _selectedVoice,
                ),
                SettingsWidget(
                  service: widget.service,
                  isCupertino: widget.isCupertino,
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