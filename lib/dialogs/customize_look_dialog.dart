import 'package:flutter/material.dart';
import 'package:wingmate/data/ui_settings.dart';

import 'package:wingmate/data/ui_settings_dao.dart';

class CustomizeLookDialog extends StatefulWidget {
  final UiSettings currentSettings;
  final UiSettingsDao uiSettingsDao;
  final Function(UiSettings) onSettingsChanged; // New callback

  const CustomizeLookDialog({
    Key? key,
    required this.currentSettings,
    required this.uiSettingsDao,
    required this.onSettingsChanged,
  }) : super(key: key);

  @override
  _CustomizeLookDialogState createState() => _CustomizeLookDialogState();
}

class _CustomizeLookDialogState extends State<CustomizeLookDialog> {
  late UiSettings _tempSettings;
  bool _isSliding = false; // New: Track if a slider is being dragged

  @override
  void initState() {
    super.initState();
    _tempSettings = UiSettings.fromMap(widget.currentSettings.toMap());
    // Ensure fieldSize is within the valid range [1.0, 8.0]
    _tempSettings.fieldSize = _tempSettings.fieldSize.clamp(1.0, 8.0);
  }

  Widget _buildSlider(String label, double value, double min, double max, ValueChanged<double> onChanged) {
    return Row(
      children: [
        Text(label),
        Expanded(
          child: Slider(
            value: value,
            min: min,
            max: max,
            onChangeStart: (val) => setState(() => _isSliding = true),
            onChangeEnd: (val) => setState(() => _isSliding = false),
            onChanged: onChanged,
          ),
        ),
        Text(value.toStringAsFixed(1)),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedOpacity(
      opacity: _isSliding ? 0.5 : 1.0, // Adjust opacity based on _isSliding
      duration: const Duration(milliseconds: 100), // Quick transition
      child: AlertDialog(
        title: const Text('Customize Look'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildSlider('Font Size', _tempSettings.fontSize, 16, 40, (val) {
                setState(() => _tempSettings.fontSize = val);
                widget.uiSettingsDao.update(_tempSettings);
                widget.onSettingsChanged(_tempSettings);
              }),
              _buildSlider('Field Size', _tempSettings.fieldSize, 1, 8, (val) {
                setState(() => _tempSettings.fieldSize = val);
                widget.uiSettingsDao.update(_tempSettings);
                widget.onSettingsChanged(_tempSettings);
              }),
              _buildSlider('Phrase Font Size', _tempSettings.phraseFontSize, 16, 30, (val) {
                setState(() => _tempSettings.phraseFontSize = val);
                widget.uiSettingsDao.update(_tempSettings);
                widget.onSettingsChanged(_tempSettings);
              }),
              _buildSlider('Phrase Width', _tempSettings.phraseWidth, 10, 200, (val) {
                setState(() => _tempSettings.phraseWidth = val);
                widget.uiSettingsDao.update(_tempSettings);
                widget.onSettingsChanged(_tempSettings);
              }),
              _buildSlider('Phrase Height', _tempSettings.phraseHeight, 10, 200, (val) {
                setState(() => _tempSettings.phraseHeight = val);
                widget.uiSettingsDao.update(_tempSettings);
                widget.onSettingsChanged(_tempSettings);
              }),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.of(context).pop(_tempSettings);
            },
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }
}