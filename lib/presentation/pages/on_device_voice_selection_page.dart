import 'package:flutter/material.dart';

class OnDeviceVoiceSelectionPage extends StatefulWidget {
  const OnDeviceVoiceSelectionPage({super.key});

  @override
  State<OnDeviceVoiceSelectionPage> createState() => _OnDeviceVoiceSelectionPageState();
}

class _OnDeviceVoiceSelectionPageState extends State<OnDeviceVoiceSelectionPage> {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Select On-Device Voice'),
      ),
      body: const Center(
        child: Text('On-device voice selection UI will be implemented here.'),
      ),
    );
  }
}
