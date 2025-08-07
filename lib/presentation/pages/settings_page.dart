import 'package:flutter/material.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/infrastructure/data/ui_settings_dao.dart';
import 'package:wingmate/presentation/widgets/customize_look_dialog.dart';
import 'package:wingmate/presentation/widgets/theme_selection_dialog.dart';

import 'package:wingmate/presentation/widgets/profile_dialog.dart';

class SettingsPage extends StatefulWidget {
  final UiSettingsDao uiSettingsDao;
  final Function(UiSettings) onSettingsSaved;
  final String speechServiceEndpoint;
  final String speechServiceKey;
  final Future<void> Function(String endpoint, String key, UiSettings uiSettings) onSaveSettings;

  const SettingsPage({
    Key? key,
    required this.uiSettingsDao,
    required this.onSettingsSaved,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
    required this.onSaveSettings,
  }) : super(key: key);

  @override
  _SettingsPageState createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late Future<List<UiSettings>> _presets;
  UiSettings? _currentSettings;

  @override
  void initState() {
    super.initState();
    _presets = widget.uiSettingsDao.getAll();
    _loadCurrentSettings();
  }

  void _loadCurrentSettings() async {
    // For now, let's just create a default setting if none exist
    var settings = await widget.uiSettingsDao.getUiSettings();
    if (settings == null) {
      settings = UiSettings(
        name: 'default',
        themeMode: ThemeMode.system,
        fontSize: 20.0,
        fieldSize: 5.0,
        phraseFontSize: 16.0,
        phraseWidth: 100.0,
        phraseHeight: 50.0,
      );
      await widget.uiSettingsDao.insert(settings);
    }
    setState(() {
      _currentSettings = settings;
    });
  }

  @override
  Widget build(BuildContext context) {
    return _currentSettings == null
        ? const Center(child: CircularProgressIndicator())
        : _buildSettingsForm();
  }

  Widget _buildSettingsForm() {
    return ListView(
      padding: const EdgeInsets.all(16.0),
      children: [
        _buildThemeSettingTile(),
        _buildCustomizeLookSettingTile(),
        _buildAzureSubscriptionSettingTile(),
        _buildPresetSelector(),
        const SizedBox(height: 20),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            ElevatedButton(
              onPressed: _revertToDefaults,
              child: const Text('Revert to Defaults'),
            ),
            ElevatedButton(
              onPressed: _saveSettings,
              child: const Text('Save Settings'),
            ),
          ],
        )
      ],
    );
  }

  Widget _buildThemeSettingTile() {
    return ListTile(
      title: const Text('Current theme'),
      subtitle: Text(_currentSettings!.themeMode.toString().split('.').last),
      onTap: () async {
        final newThemeMode = await showDialog<ThemeMode>(
          context: context,
          builder: (BuildContext context) {
            return ThemeSelectionDialog(
              currentSettings: _currentSettings!,
              onThemeModeChanged: (themeMode) {
                setState(() {
                  _currentSettings = _currentSettings!.copyWith(themeMode: themeMode);
                });
              },
            );
          },
        );
        if (newThemeMode != null) {
          setState(() {
            _currentSettings = _currentSettings!.copyWith(themeMode: newThemeMode);
          });
        }
      },
    );
  }

  Widget _buildCustomizeLookSettingTile() {
    return ListTile(
      title: const Text('Customize look'),
      onTap: () async {
        // Pop the SettingsPage immediately
        Navigator.of(context).pop();

        // Show the CustomizeLookDialog using the root navigator's context
        // This ensures the dialog is shown even if the current route is dismissed
        final updatedSettings = await showDialog<UiSettings>(
          context: Navigator.of(context, rootNavigator: true).context,
          barrierColor: Colors.black54, // Dim the background to make it appear hidden
          builder: (BuildContext context) {
            return CustomizeLookDialog(
              currentSettings: _currentSettings!,
              uiSettingsDao: widget.uiSettingsDao,
              onSettingsChanged: widget.onSettingsSaved,
            );
          },
        );

        // Handle updated settings if the dialog returns a value
        if (updatedSettings != null) {
          // Since SettingsPage is already popped, we update the main page directly
          widget.onSettingsSaved(updatedSettings);
        }
      }
    );
  }

  Widget _buildPresetSelector() {
    return FutureBuilder<List<UiSettings>>(
      future: _presets,
      builder: (context, snapshot) {
        if (snapshot.hasData) {
          return DropdownButtonFormField<UiSettings>(
            decoration: const InputDecoration(labelText: 'Preset'),
            items: snapshot.data!.map((preset) {
              return DropdownMenuItem(value: preset, child: Text(preset.name));
            }).toList(),
            onChanged: (UiSettings? newValue) {
              if (newValue != null) {
                setState(() {
                  _currentSettings = newValue;
                });
              }
            },
          );
        } else {
          return const SizedBox.shrink();
        }
      },
    );
  }

  Widget _buildAzureSubscriptionSettingTile() {
    return ListTile(
      title: const Text('Azure Subscription Settings'),
      onTap: () {
        showProfileDialog(
          context,
          widget.speechServiceEndpoint,
          widget.speechServiceKey,
          _currentSettings!,
          (endpoint, key, uiSettings) => widget.onSaveSettings(endpoint, key, uiSettings as UiSettings),
        );
      },
    );
  }

  void _revertToDefaults() async {
    // Delete the existing default settings by name
    await widget.uiSettingsDao.delete(_currentSettings!.id!);

    // Create new default settings
    final defaultSettings = UiSettings(
      name: 'default',
      themeMode: ThemeMode.system,
      fontSize: 20.0,
      fieldSize: 5.0,
      phraseFontSize: 16.0,
      phraseWidth: 100.0,
      phraseHeight: 50.0,
    );

    // Insert the new default settings
    await widget.uiSettingsDao.insert(defaultSettings);

    setState(() {
      _currentSettings = defaultSettings;
    });
    widget.onSettingsSaved(_currentSettings!);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Settings reverted to defaults!')));
  }

  void _saveSettings() async {
    if (_currentSettings != null) {
      await widget.uiSettingsDao.saveUiSettings(_currentSettings!);
      widget.onSettingsSaved(_currentSettings!);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Settings saved!')),);
    }
  }
}