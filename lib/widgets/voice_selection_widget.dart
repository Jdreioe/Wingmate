import 'package:flutter/cupertino.dart';
// Removed material.dart as it is no longer used
// Removed foundation.dart as it is no longer used
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/voice_settings_service.dart';

class VoiceSelectionWidget extends StatelessWidget {
  final VoiceSettingsService service;
  final List<Voice> voices;
  final Voice? selectedVoice;

  const VoiceSelectionWidget({
    Key? key,
    required this.service,
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
            style: CupertinoTheme.of(context).textTheme.navTitleTextStyle,
          ),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: voices.length,
            itemBuilder: (context, index) {
              final voice = voices[index];
              final isSelected = selectedVoice?.name == voice.name;
              // Now always uses the Cupertino version of the tile
              return _buildVoiceTile(context, voice, isSelected);
            },
          ),
        ),
      ],
    );
  }

  Widget _buildVoiceTile(BuildContext context, Voice voice, bool isSelected) {
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
  }
}