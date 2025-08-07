import 'package:flutter/material.dart';
import 'package:wingmate/infrastructure/models/ui_settings.dart' as ui_settings_model;
import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/infrastructure/data/ui_settings_dao.dart';

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
  late ui_settings_model.UiSettings _tempSettings;
  bool _isSliding = false; // New: Track if a slider is being dragged

  @override
  void initState() {
    super.initState();
    _tempSettings = ui_settings_model.UiSettings.fromDomain(widget.currentSettings);
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
                setState(() => _tempSettings = _tempSettings.copyWith(fontSize: val));
                widget.uiSettingsDao.saveUiSettings(_tempSettings.toDomain());
                widget.onSettingsChanged(_tempSettings.toDomain());
              }),
              _buildSlider('Field Size', _tempSettings.fieldSize, 1, 8, (val) {
                setState(() => _tempSettings = _tempSettings.copyWith(fieldSize: val));
                widget.uiSettingsDao.saveUiSettings(_tempSettings.toDomain());
                widget.onSettingsChanged(_tempSettings.toDomain());
              }),
              _buildSlider('Phrase Font Size', _tempSettings.phraseFontSize, 16, 30, (val) {
                setState(() => _tempSettings = _tempSettings.copyWith(phraseFontSize: val));
                widget.uiSettingsDao.saveUiSettings(_tempSettings.toDomain());
                widget.onSettingsChanged(_tempSettings.toDomain());
              }),
              _buildSlider('Phrase Width', _tempSettings.phraseWidth, 10, 200, (val) {
                setState(() => _tempSettings = _tempSettings.copyWith(phraseWidth: val));
                widget.uiSettingsDao.saveUiSettings(_tempSettings.toDomain());
                widget.onSettingsChanged(_tempSettings.toDomain());
              }),
              _buildSlider('Phrase Height', _tempSettings.phraseHeight, 10, 200, (val) {
                setState(() => _tempSettings = _tempSettings.copyWith(phraseHeight: val));
                widget.uiSettingsDao.saveUiSettings(_tempSettings.toDomain());
                widget.onSettingsChanged(_tempSettings.toDomain());
              }),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.of(context).pop(_tempSettings.toDomain());
            },
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }
}