import 'package:flutter/material.dart';
import 'package:wingmate/infrastructure/config/speech_service_config.dart';
import 'package:wingmate/infrastructure/services/voice_settings_service.dart';

class SettingsWidget extends StatelessWidget {
  final VoiceSettingsService service;
  final SpeechServiceConfig config;

  const SettingsWidget({
    Key? key,
    required this.service,
    required this.config,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.all(16.0),
          child: Text(
            'Settings',
            style: Theme.of(context).textTheme.titleLarge,
          ),
        ),
        _buildSettingTile(
          context,
          'Endpoint',
          config.endpoint,
          (value) => service.saveSpeechServiceConfig(
            SpeechServiceConfig(
              endpoint: value,
              key: config.key,
            ),
          ),
        ),
        _buildSettingTile(
          context,
          'Subscription Key',
          config.key,
          (value) => service.saveSpeechServiceConfig(
            SpeechServiceConfig(
              endpoint: config.endpoint,
              key: value,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSettingTile(
    BuildContext context,
    String title,
    String value,
    Function(String) onChanged,
  ) {
    return ListTile(
      title: Text(title),
      trailing: TextField(
        decoration: InputDecoration(
          hintText: value,
        ),
        onChanged: onChanged,
      ),
    );
  }
} 