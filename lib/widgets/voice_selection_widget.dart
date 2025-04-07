import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/voice_settings_service.dart';

class VoiceSelectionWidget extends StatelessWidget {
  final VoiceSettingsService service;
  final bool isCupertino;
  final List<Voice> voices;
  final Voice? selectedVoice;

  const VoiceSelectionWidget({
    Key? key,
    required this.service,
    required this.isCupertino,
    required this.voices,
    required this.selectedVoice,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.all(16.0),
          child: Text(
            'Select Voice',
            style: isCupertino
                ? CupertinoTheme.of(context).textTheme.navTitleTextStyle
                : Theme.of(context).textTheme.titleLarge,
          ),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: voices.length,
            itemBuilder: (context, index) {
              final voice = voices[index];
              final isSelected = selectedVoice?.name == voice.name;
              return _buildVoiceTile(context, voice, isSelected);
            },
          ),
        ),
      ],
    );
  }

  Widget _buildVoiceTile(BuildContext context, Voice voice, bool isSelected) {
    if (isCupertino) {
      return CupertinoListTile(
        title: Text(voice.name),
        subtitle: Text(voice.selectedLanguage),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (isSelected) const Icon(CupertinoIcons.check_mark),
            CupertinoButton(
              padding: EdgeInsets.zero,
              onPressed: () => service.testVoice(voice),
              child: const Icon(CupertinoIcons.play),
            ),
          ],
        ),
        onTap: () => service.saveVoice(voice),
      );
    } else {
      return ListTile(
        title: Text(voice.name),
        subtitle: Text(voice.selectedLanguage),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (isSelected) const Icon(Icons.check),
            IconButton(
              icon: const Icon(Icons.play_arrow),
              onPressed: () => service.testVoice(voice),
            ),
          ],
        ),
        onTap: () => service.saveVoice(voice),
      );
    }
  }
} 