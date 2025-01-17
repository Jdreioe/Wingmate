import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart'; // Add this import

class VoiceSettingsDialog extends StatefulWidget {
  final String displayName;
  final List<String> supportedLanguages;
  final String shortName;
  final Function(String, double, double, String, String) onSave;

  VoiceSettingsDialog({
    required this.displayName,
    required this.shortName,
    required this.supportedLanguages,
    required this.onSave,
  });

  @override
  _VoiceSettingsDialogState createState() => _VoiceSettingsDialogState();
}

class _VoiceSettingsDialogState extends State<VoiceSettingsDialog> {
  late String _selectedLanguage;
  double _pitch = 1.0;
  double _rate = 1.0;
  final Map<double, String> _pitchLabels = {
    0.5: 'x-low',
    0.875: 'low',
    1.25: 'medium',
    1.625: 'high',
    2.0: 'x-high',
  };
  final Map<double, String> _rateLabels = {
    0.5: 'x-slow',
    0.875: 'slow',
    1.25: 'medium',
    1.625: 'high',
    2.0: 'x-high',
  };

  late AzureTts azureTts; // Add this line

  @override
  void initState() {
    super.initState();
    final voiceBox = Hive.box('selectedVoice');
    final currentVoice = voiceBox.get('currentVoice') as Voice?;
    if (currentVoice != null && currentVoice.name == widget.shortName) {
      _selectedLanguage = currentVoice.selectedLanguage;
      _pitch = currentVoice.pitch;
      _rate = currentVoice.rate;
    } else {
      _selectedLanguage = widget.supportedLanguages.first;
    }
    final settingsBox = Hive.box('settings');
    final config = settingsBox.get('config');
    final subscriptionKey = config.key as String;
    final region = config.endpoint as String;
    azureTts = AzureTts(
      subscriptionKey: subscriptionKey, // Replace with your Azure subscription key
      region: region, // Replace with your Azure region
      settingsBox: settingsBox,
      messageController: TextEditingController(),
      voiceBox: Hive.box('selectedVoice'),
      context: context,
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Voice Settings - ${widget.displayName}'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(height: 16.0),
          if (widget.supportedLanguages.contains('en-US'))...[
          const Text("Select Language: "),
          DropdownButton<String>(
            value: _selectedLanguage,
            onChanged: (String? newValue) {
              if (newValue != null) {
                setState(() {
                  _selectedLanguage = newValue;
                });
              }
            },
            items: widget.supportedLanguages
                .map<DropdownMenuItem<String>>((String value) {
              return DropdownMenuItem<String>(
                value: value,
                child: Text(value),
              );
            }).toList(),
          ),
          ],
          SizedBox(height: 16.0),

          if (!widget.supportedLanguages.contains('en-US')) ...[
            const Text("Select pitch: "),
            Slider(
              value: _pitch,
              min: 0.5,
              max: 2.0,
              divisions: 4,
              label: _pitchLabels[_pitch] ?? "medium",
              onChanged: (double value) {
                setState(() {
                  debugPrint("$value");
                  _pitch = value;
                });
              },
            ),
            SizedBox(height: 16.0),
            const Text("Rate"),
            Slider(
              value: _rate,
              min: 0.5,
              max: 2.0,
              divisions: 4,
              label: _rateLabels[_rate],
              onChanged: (double value) {
                setState(() {
                  _rate = value;
                });
              },
            ),
          ],
          SizedBox(height: 16.0),
          ElevatedButton(
            onPressed: () async {
              widget.onSave(_selectedLanguage, _pitch, _rate, _pitchLabels[_pitch].toString(), _rateLabels[_rate].toString());
              await azureTts.generateSSML("This is a test of the voice settings.");
            },
            child: Text('Test Voice'),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.pop(context);
          },
          child: Text('Cancel'),
        ),
        TextButton(
          onPressed: () {
            widget.onSave(_selectedLanguage, _pitch, _rate, _pitchLabels[_pitch].toString(), _rateLabels[_rate].toString());
            Navigator.pop(context);
          },
          child: Text('Save'),
        ),
      ],
    );
  }
}
