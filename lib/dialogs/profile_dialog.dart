import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

void showProfileDialog(
  BuildContext context,
  String speechServiceEndpoint,
  String speechServiceKey,
  Future<void> Function(String endpoint, String key) onSaveSettings,
) async {
  debugPrint('Showing profile dialog');
  final endpointController = TextEditingController(text: speechServiceEndpoint);
  final keyController = TextEditingController(text: speechServiceKey);

  // Consolidated into a single Material dialog for all platforms
  showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Profile Settings'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: endpointController,
            decoration: const InputDecoration(labelText: 'Region'),
          ),
          TextField(
            controller: keyController,
            decoration: const InputDecoration(labelText: 'Key'),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.pop(context);
          },
          child: const Text('Close'),
        ),
        TextButton(
          onPressed: () async {
            await onSaveSettings(endpointController.text, keyController.text);
            Navigator.pop(context);
          },
          child: const Text('Save'),
        ),
      ],
    ),
  );
}