import 'package:flutter/material.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

class ThemeSelectionDialog extends StatelessWidget {
  final UiSettings currentSettings;
  final ValueChanged<ThemeMode> onThemeModeChanged;

  const ThemeSelectionDialog({
    Key? key,
    required this.currentSettings,
    required this.onThemeModeChanged,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Select Theme'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          RadioListTile<ThemeMode>(
            title: const Text('System'),
            value: ThemeMode.system,
            groupValue: currentSettings.themeMode,
            onChanged: (ThemeMode? value) {
              if (value != null) {
                onThemeModeChanged(value);
                Navigator.of(context).pop();
              }
            },
          ),
          RadioListTile<ThemeMode>(
            title: const Text('Light'),
            value: ThemeMode.light,
            groupValue: currentSettings.themeMode,
            onChanged: (ThemeMode? value) {
              if (value != null) {
                onThemeModeChanged(value);
                Navigator.of(context).pop();
              }
            },
          ),
          RadioListTile<ThemeMode>(
            title: const Text('Dark'),
            value: ThemeMode.dark,
            groupValue: currentSettings.themeMode,
            onChanged: (ThemeMode? value) {
              if (value != null) {
                onThemeModeChanged(value);
                Navigator.of(context).pop();
              }
            },
          ),
        ],
      ),
    );
  }
}