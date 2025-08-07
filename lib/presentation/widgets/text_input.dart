import 'package:flutter/material.dart';
import 'package:wingmate/presentation/widgets/styled_text_controller.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

class TextInput extends StatefulWidget {
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
  _TextInputState createState() => _TextInputState();
}

class _TextInputState extends State<TextInput> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(() {
      setState(() {});
    });
  }

  @override
  Widget build(BuildContext context) {
    final double collapsedHeight = widget.uiSettings.fieldSize * widget.uiSettings.fontSize;
    final double lineHeight = widget.uiSettings.fontSize * 1.2; // Approximate line height
    final double expandedHeight = lineHeight * widget.maxLines + 20.0; // Add some padding

    return AnimatedBuilder(
      animation: widget.animation,
      builder: (context, child) {
        return AnimatedSize(
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeInOut,
          child: SizedBox(
            height: collapsedHeight + (expandedHeight - collapsedHeight) * widget.animation.value,
            child: TextField(
              controller: widget.controller,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Enter text',
              ),
              maxLines: widget.maxLines,
              textAlignVertical: TextAlignVertical.top,
            ),
          ),
        );
      },
    );
  }
}
