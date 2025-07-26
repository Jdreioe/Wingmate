import 'package:flutter/material.dart';
import 'package:wingmate/widgets/styled_text_controller.dart';
import 'package:wingmate/data/ui_settings.dart';

class TextInput extends StatelessWidget {
  final StyledTextController controller;
  final bool isExpanded;
  final Animation<double> animation;
  final int maxLines;
  final UiSettings uiSettings;

  const TextInput({
    Key? key,
    required this.controller,
    required this.isExpanded,
    required this.animation,
    required this.maxLines,
    required this.uiSettings,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final double collapsedHeight = uiSettings.fieldSize * uiSettings.fontSize;
    final double lineHeight = uiSettings.fontSize * 1.2; // Approximate line height
    final double expandedHeight = lineHeight * maxLines + 20.0; // Add some padding

    return AnimatedBuilder(
      animation: animation,
      builder: (context, child) {
        return AnimatedSize(
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeInOut,
          child: SizedBox(
            height: collapsedHeight + (expandedHeight - collapsedHeight) * animation.value,
            child: TextField(
              controller: controller,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Enter text',
              ),
              maxLines: maxLines,
              style: TextStyle(fontSize: uiSettings.fontSize),
              textAlignVertical: TextAlignVertical.top,
            ),
          ),
        );
      },
    );
  }
}
