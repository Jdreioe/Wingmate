import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:wingmate/data/ui_settings.dart';

// Safely import Platform
import 'dart:io' as io show Platform;
import 'package:wingmate/ui/fetch_voices_page.dart';
// Safe platform check
bool get isIOS => !kIsWeb && io.Platform.isIOS;

void showProfileDialog(
  BuildContext context,
  String speechServiceEndpoint,
  String speechServiceKey,
  UiSettings uiSettings,
  Future<void> Function(String endpoint, String key, UiSettings uiSettings) onSaveSettings,
) async {
  debugPrint('Showing profile dialog');
  final endpointController = TextEditingController(text: speechServiceEndpoint);
  final keyController = TextEditingController(text: speechServiceKey);

  // Use Material dialog for web
  if (kIsWeb) {
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
            onPressed: () {
              Navigator.pop(context);
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => FetchVoicesPage(
                    endpoint: endpointController.text,
                    subscriptionKey: keyController.text,
                    uiSettings: uiSettings,
                    onSaveSettings: onSaveSettings,
                  ),
                ),
              );
            },
            child: const Text('Fetch Voices'),
          ),
          TextButton(
            onPressed: () async {
              await onSaveSettings(endpointController.text, keyController.text, uiSettings);
              Navigator.pop(context);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  } 
  // Use Cupertino for iOS
  else if (isIOS) {
    showCupertinoDialog<void>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: const Text('Profile Settings'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CupertinoTextField(
              controller: endpointController,
              placeholder: 'Region',
            ),
            CupertinoTextField(
              controller: keyController,
              placeholder: 'Key',
            ),
          ],
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () {
              Navigator.pop(context);
            },
            child: const Text('Fetch Voices'),
          ),
          CupertinoDialogAction(
            onPressed: () {
              Navigator.pop(context);
            },
            child: const Text('Close'),
          ),
          CupertinoDialogAction(
            onPressed: () async {
              await onSaveSettings(endpointController.text, keyController.text, uiSettings);
              Navigator.pop(context);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  } 
  // Use Material for other platforms
  else {
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
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => FetchVoicesPage(
                    endpoint: endpointController.text,
                    subscriptionKey: keyController.text,
                    uiSettings: uiSettings,
                    onSaveSettings: onSaveSettings,
                  ),
                ),
              );
            },
            child: const Text('Fetch Voices'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
            },
            child: const Text('Close'),
          ),
          TextButton(
            onPressed: () async {
              await onSaveSettings(endpointController.text, keyController.text, uiSettings);
              Navigator.pop(context);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }
}

