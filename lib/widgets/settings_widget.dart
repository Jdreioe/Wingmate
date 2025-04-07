import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:wingmate/services/voice_settings_service.dart';
import 'package:wingmate/utils/speech_service_config.dart';

class SettingsWidget extends StatelessWidget {
  final VoiceSettingsService service;
  final bool isCupertino;
  final SpeechServiceConfig config;

  const SettingsWidget({
    Key? key,
    required this.service,
    required this.isCupertino,
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
            style: isCupertino
                ? CupertinoTheme.of(context).textTheme.navTitleTextStyle
                : Theme.of(context).textTheme.titleLarge,
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
    if (isCupertino) {
      return CupertinoListTile(
        title: Text(title),
        trailing: CupertinoTextField(
          placeholder: value,
          onChanged: onChanged,
        ),
      );
    } else {
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
} 