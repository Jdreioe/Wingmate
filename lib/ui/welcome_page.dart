import 'package:flutter/material.dart';
import 'package:wingmate/data/ui_settings.dart';
import 'package:wingmate/ui/fetch_voices_page.dart';
import 'package:wingmate/ui/on_device_voice_selection_page.dart';

class WelcomePage extends StatefulWidget {
  final Function(String endpoint, String key, UiSettings uiSettings) onSettingsSaved;
  final UiSettings uiSettings;
  final Future<void> Function(String endpoint, String key, UiSettings uiSettings) onSaveSettings;

  const WelcomePage({super.key, required this.onSettingsSaved, required this.uiSettings, required this.onSaveSettings});

  @override
  State<WelcomePage> createState() => _WelcomePageState();
}

class _WelcomePageState extends State<WelcomePage> {
  String? _selectedVoiceProvider;
  final TextEditingController _endpointController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();

  @override
  void dispose() {
    _endpointController.dispose();
    _apiKeyController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Welcome'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'New user',
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            const Text('Tell us about yourself'),
            const SizedBox(height: 24),
            Expanded(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Profile Section
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Profile',
                          style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 16),
                        TextField(
                          decoration: InputDecoration(
                            labelText: 'Name',
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 16),
                        DropdownButtonFormField<String>(
                          decoration: InputDecoration(
                            labelText: 'First language',
                            border: OutlineInputBorder(),
                          ),
                          items: const [
                            DropdownMenuItem(
                              value: 'English (United States)',
                              child: Text('English (United States)'),
                            ),
                            // Add more languages as needed
                          ],
                          onChanged: (value) {
                            // Handle language selection
                          },
                          value: 'English (United States)', // Default value
                        ),
                        const SizedBox(height: 16),
                        Center(
                          child: Column(
                            children: [
                              CircleAvatar(
                                radius: 60,
                                backgroundColor: Colors.grey[300],
                                child: Icon(
                                  Icons.person,
                                  size: 60,
                                  color: Colors.grey[600],
                                ),
                              ),
                              const SizedBox(height: 8),
                              ElevatedButton(
                                onPressed: () {
                                  // Handle change picture
                                },
                                child: const Text('Change Picture'),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 32),
                  // Voice Section
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Voice',
                          style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 16),
                        DropdownButtonFormField<String>(
                          decoration: InputDecoration(
                            labelText: 'Voice Provider',
                            border: OutlineInputBorder(),
                          ),
                          value: _selectedVoiceProvider,
                          items: const [
                            DropdownMenuItem(
                              value: 'Azure',
                              child: Text('Azure'),
                            ),
                            DropdownMenuItem(
                              value: 'Google Cloud',
                              child: Text('Google Cloud'),
                            ),
                            DropdownMenuItem(
                              value: 'ElevenLabs',
                              child: Text('ElevenLabs'),
                            ),
                            DropdownMenuItem(
                              value: 'On-device',
                              child: Text('On-device'),
                            ),
                          ],
                          onChanged: (value) {
                            setState(() {
                              _selectedVoiceProvider = value;
                            });
                          },
                        ),
                        const SizedBox(height: 16),
                        if (_selectedVoiceProvider == 'Azure') ...[
                          TextField(
                            controller: _endpointController,
                            decoration: InputDecoration(
                              labelText: 'Region (endpoint)',
                              border: OutlineInputBorder(),
                            ),
                          ),
                          const SizedBox(height: 16),
                          TextField(
                            controller: _apiKeyController,
                            decoration: InputDecoration(
                              labelText: 'API Key (key)',
                              border: OutlineInputBorder(),
                            ),
                          ),
                        ] else if (_selectedVoiceProvider == 'Google Cloud' ||
                            _selectedVoiceProvider == 'ElevenLabs') ...[
                          TextField(
                            readOnly: true,
                            decoration: InputDecoration(
                              labelText: 'Not yet implemented',
                              border: OutlineInputBorder(),
                            ),
                          ),
                        ],
                        // Placeholder for other voice settings (speed, pitch)
                        const SizedBox(height: 16),
                        Row(
                          children: [
                            Expanded(
                              child: ElevatedButton(
                                onPressed: () {
                                  // Handle Change Voice
                                },
                                child: const Text('Change'),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: ElevatedButton(
                                onPressed: () {
                                  // Handle Test Voice
                                },
                                child: const Text('Test Voice'),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 16),
                        const Text('Speed'),
                        Slider(value: 0.5, onChanged: (value) {}),
                        const Text('Pitch'),
                        Slider(value: 0.5, onChanged: (value) {}),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            Align(
              alignment: Alignment.bottomRight,
              child: ElevatedButton(
                onPressed: () {
                  if (_selectedVoiceProvider == 'Azure') {
                    if (_endpointController.text.isNotEmpty && _apiKeyController.text.isNotEmpty) {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => FetchVoicesPage(
                            endpoint: _endpointController.text,
                            subscriptionKey: _apiKeyController.text,
                            uiSettings: widget.uiSettings,
                            onSaveSettings: widget.onSaveSettings,
                          ),
                        ),
                      );
                    } else {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Please enter Azure endpoint and API key.')),
                      );
                    }
                  } else if (_selectedVoiceProvider == 'On-device') {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => const OnDeviceVoiceSelectionPage(),
                      ),
                    );
                  } else {
                    widget.onSaveSettings(
                        _endpointController.text,
                        _apiKeyController.text,
                        UiSettings(name: 'default', themeMode: ThemeMode.system));
                    widget.onSettingsSaved(
                        _endpointController.text,
                        _apiKeyController.text,
                        UiSettings(name: 'default', themeMode: ThemeMode.system));
                  }
                },
                child: const Text('Next'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
