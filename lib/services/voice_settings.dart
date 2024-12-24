import 'package:flutter/material.dart';

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
    _selectedLanguage = widget.supportedLanguages.first;
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
