import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/domain/entities/voice.dart' as domain_models;
import 'package:wingmate/infrastructure/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/infrastructure/config/speech_service_config.dart';
import 'package:wingmate/infrastructure/data/said_text_dao.dart';

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

  late AzureTts azureTts;
  late SaidTextDao _saidTextDao;

  @override
  void initState() {
    super.initState();
    debugPrint('[VoiceSettingsDialog] Initializing with displayName: ${widget.displayName}, shortName: ${widget.shortName}');
    final voiceBox = Hive.box('selectedVoice');
    final currentVoice = voiceBox.get('currentVoice') as domain_models.Voice?;
    if (currentVoice != null && currentVoice.name == widget.shortName) {
      _selectedLanguage = currentVoice.primaryLanguage ?? widget.supportedLanguages.first;
      _pitch = currentVoice.pitch ?? 1.0;
      _rate = currentVoice.rate ?? 1.0;
      debugPrint('[VoiceSettingsDialog] Found existing voice settings: language=$_selectedLanguage, pitch=$_pitch, rate=$_rate');
    } else {
      _selectedLanguage = widget.supportedLanguages.first;
      debugPrint('[VoiceSettingsDialog] No existing voice settings found, using default language: $_selectedLanguage');
    }
    _saidTextDao = SaidTextDao(AppDatabase());
    final settingsBox = Hive.box('settings');
    final config = settingsBox.get('config') as SpeechServiceConfig?;
    if (config != null) {
      azureTts = AzureTts(
        subscriptionKey: config.key,
        region: config.endpoint,
        settingsBox: settingsBox,
        voiceBox: Hive.box('selectedVoice'),
        context: context,
        saidTextDao: _saidTextDao,
      );
      debugPrint('[VoiceSettingsDialog] AzureTts initialized.');
    }
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
          SizedBox(height: 16.0),
          ElevatedButton(
            onPressed: () async {
              // Create a temporary voice model to test with the selected settings
              final testVoice = domain_models.Voice(
                name: widget.shortName,
                primaryLanguage: _selectedLanguage,
                pitch: _pitch,
                rate: _rate,
                pitchForSSML: _pitchLabels[_pitch].toString(),
                rateForSSML: _rateLabels[_rate].toString(),
                supportedLanguages: widget.supportedLanguages,
                selectedLanguage: _selectedLanguage,
              );
              await azureTts.testVoice(
                "This is a test of the voice settings.",
                testVoice,
              );
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
            debugPrint('[VoiceSettingsDialog] Saving voice settings: language=$_selectedLanguage, pitch=$_pitch, rate=$_rate');
            widget.onSave(_selectedLanguage, _pitch, _rate, _pitchLabels[_pitch].toString(), _rateLabels[_rate].toString());
            Navigator.pop(context);
          },
          child: Text('Save'),
        ),
      ],
    );
  }
}
