import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/voice_settings_service.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/widgets/voice_selection_widget.dart';
import 'package:wingmate/widgets/settings_widget.dart';

Future<void> showVoiceSettingsDialog(
  BuildContext context,
  String endpoint,
  String subscriptionKey,
  Function(String, String) onSaveSettings,
) async {
  final voiceBox = Hive.box('selectedVoice');
  final settingsBox = Hive.box('settings');

  final service = VoiceSettingsService(
    voiceBox: voiceBox,
    settingsBox: settingsBox,
    endpoint: endpoint,
    subscriptionKey: subscriptionKey,
    onSaveSettings: onSaveSettings,
    context: context,
  );

  // Consolidated to use showCupertinoModalPopup for all platforms
  await showCupertinoModalPopup(
    context: context,
    builder: (context) => VoiceSettingsDialog(
      service: service,
    ),
  );
}

class VoiceSettingsDialog extends StatefulWidget {
  final VoiceSettingsService service;

  // The isCupertino parameter was removed as the entire app is now Cupertino
  const VoiceSettingsDialog({
    Key? key,
    required this.service,
  }) : super(key: key);

  @override
  _VoiceSettingsDialogState createState() => _VoiceSettingsDialogState();
}

class _VoiceSettingsDialogState extends State<VoiceSettingsDialog> {
  late Voice? _selectedVoice;
  late SpeechServiceConfig _config;
  bool _isLoading = true;
  int _selectedTab = 0; // 0 for Voices, 1 for Settings

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    try {
      // Corrected to fetch the list of voices
      _selectedVoice = widget.service.getSelectedVoice();
      _config = widget.service.getSpeechServiceConfig() ??
          SpeechServiceConfig(
            endpoint: widget.service.endpoint,
            key: widget.service.subscriptionKey,
          );
    } finally {
      setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    // Consolidated to a single CupertinoPageScaffold for all platforms
    return CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(
        middle: const Text('Voice Settings'),
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => Navigator.pop(context),
          child: const Text('Done'),
        ),
      ),
      child: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(8.0),
              child: CupertinoSlidingSegmentedControl<int>(
                groupValue: _selectedTab,
                onValueChanged: (int? newValue) {
                  if (newValue != null) {
                    setState(() {
                      _selectedTab = newValue;
                    });
                  }
                },
                children: const {
                  0: Padding(
                    padding: EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                    child: Text('VOICES'),
                  ),
                  1: Padding(
                    padding: EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                    child: Text('SETTINGS'),
                  ),
                },
              ),
            ),
            Expanded(
              child: _isLoading
                  ? const Center(child: CupertinoActivityIndicator())
                  : IndexedStack(
                      index: _selectedTab,
                      children: [
                        VoiceSelectionWidget(
                          service: widget.service,
                          voices: [],
                          selectedVoice: _selectedVoice,
                        ),
                        SettingsWidget(
                          service: widget.service,
                          config: _config,
                        ),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}