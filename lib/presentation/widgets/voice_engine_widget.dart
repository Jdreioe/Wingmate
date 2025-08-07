import 'package:flutter/material.dart';
import 'package:wingmate/infrastructure/services/voice_settings_service.dart';
import 'package:wingmate/infrastructure/config/speech_service_config.dart';
import 'package:wingmate/presentation/widgets/profile_dialog.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

class VoiceEngineWidget extends StatefulWidget {
  final VoiceSettingsService service;
  final SpeechServiceConfig config;
  final UiSettings uiSettings;
  final Future<void> Function(String, String, UiSettings) onSaveSettings;

  const VoiceEngineWidget({
    Key? key,
    required this.service,
    required this.config,
    required this.uiSettings,
    required this.onSaveSettings,
  }) : super(key: key);

  @override
  _VoiceEngineWidgetState createState() => _VoiceEngineWidgetState();
}

class _VoiceEngineWidgetState extends State<VoiceEngineWidget> {
  String _selectedEngine = 'Azure';

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.all(16.0),
          child: Text(
            'Voice Engine Settings',
            style: Theme.of(context).textTheme.titleLarge,
          ),
        ),
        ListTile(
          title: const Text('Voice Engine'),
          trailing: DropdownButton<String>(
            value: _selectedEngine,
            items: ['On device', 'Azure'].map((String value) {
              return DropdownMenuItem<String>(
                value: value,
                child: Text(value),
              );
            }).toList(),
            onChanged: (String? newValue) async {
              if (newValue != null) {
                setState(() {
                  _selectedEngine = newValue;
                });
              }
            },
          ),
        ),
        if (_selectedEngine == 'Azure') ...[
          ListTile(
            title: const Text('Azure Configuration'),
            trailing: ElevatedButton(
              onPressed: () {
                showProfileDialog(
                  context,
                  widget.config.endpoint,
                  widget.config.key,
                  widget.uiSettings,
                  widget.onSaveSettings,
                );
              },
              child: const Text('Configure'),
            ),
          )
        ],
      ],
    );
  }
}
