import 'package:flutter/cupertino.dart';
// Removed material.dart as it is no longer used
import 'package:wingmate/services/voice_settings_service.dart';
import 'package:wingmate/utils/speech_service_config.dart';

class SettingsWidget extends StatelessWidget {
  final VoiceSettingsService service;
  final SpeechServiceConfig config;

  // The isCupertino parameter was removed as the entire app is now Cupertino
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
            style: CupertinoTheme.of(context).textTheme.navTitleTextStyle
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
    // Consolidated to a single Cupertino widget as the platform check is no longer needed
    return CupertinoListTile(
      title: Text(title),
      trailing: CupertinoTextField(
        placeholder: value,
        onChanged: onChanged,
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
        decoration: BoxDecoration(
          color: CupertinoColors.white,
          border: Border.all(color: CupertinoColors.systemGrey),
          borderRadius: BorderRadius.circular(5.0),
        ),
      ),
    );
  }
}