import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmancrossplatform/models/voice_model.dart';

class VoiceSettingsDialog extends StatefulWidget {
  final String displayName;
  final List<String> supportedLanguages;
  final String shortName;
  final Function(String, double, double) onSave;

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
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Voice Settings - ${widget.displayName}'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
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
          Slider(
            value: _pitch,
            min: 0.5,
            max: 2.0,
            divisions: 10,
            label: "Pitch: $_pitch",
            onChanged: (double value) {
              setState(() {
                _pitch = value;
              });
            },
          ),
          Slider(
            value: _rate,
            min: 0.5,
            max: 2.0,
            divisions: 10,
            label: "Rate: $_rate",
            onChanged: (double value) {
              setState(() {
                _rate = value;
              });
            },
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
            widget.onSave(_selectedLanguage, _pitch, _rate);
            Navigator.pop(context);
          },
          child: Text('Save'),
        ),
      ],
    );
  }
}
