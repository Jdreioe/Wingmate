import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart'; // Import for Cupertino widgets
import 'dart:io'; // Import for Platform.isIOS

void showProfileDialog(
  BuildContext context,
  String speechServiceEndpoint,
  String speechServiceKey,
  Future<void> Function(String endpoint, String key) onSaveSettings,
) {
  debugPrint('Showing profile dialog');
  final endpointController = TextEditingController(text: speechServiceEndpoint);
  final keyController = TextEditingController(text: speechServiceKey);

  // Use CupertinoAlertDialog for iOS, AlertDialog for Android
  if (Platform.isIOS) {
    showCupertinoDialog<void>( // Use showCupertinoDialog
      context: context,
      builder: (context) => CupertinoAlertDialog( // Use CupertinoAlertDialog
        title: const Text('Profile Settings'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CupertinoTextField( // Use CupertinoTextField
              controller: endpointController,
              placeholder: 'Region', // Use placeholder instead of labelText
            ),
            CupertinoTextField( // Use CupertinoTextField
              controller: keyController,
              placeholder: 'Key', // Use placeholder instead of labelText
            ),
          ],
        ),
        actions: [
          CupertinoDialogAction( // Use CupertinoDialogAction
            onPressed: () {
              Navigator.pop(context);
            },
            child: const Text('Close'),
          ),
          CupertinoDialogAction( // Use CupertinoDialogAction
            onPressed: () async {
              await onSaveSettings(endpointController.text, keyController.text);
              Navigator.pop(context);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  } else {
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
}

