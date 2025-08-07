import 'package:flutter/material.dart';

class TopBar extends StatelessWidget {
  final VoidCallback onWrapWithLangTag;
  final VoidCallback onHistoryPressed;
  final VoidCallback onSettingsPressed;
  final Widget primaryLanguageSelector;
  final Widget secondaryLanguageSelector;
  final bool showWrapWithLangTag;

  const TopBar({
    Key? key,
    required this.onWrapWithLangTag,
    required this.onHistoryPressed,
    required this.onSettingsPressed,
    required this.primaryLanguageSelector,
    required this.secondaryLanguageSelector,
    required this.showWrapWithLangTag,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        primaryLanguageSelector,
        Row(
          children: [
            secondaryLanguageSelector,
            if (showWrapWithLangTag)
              IconButton(
                icon: const Icon(Icons.add_circle_outline),
                onPressed: onWrapWithLangTag,
              ),
            IconButton(
              icon: const Icon(Icons.history),
              onPressed: onHistoryPressed,
            ),
            IconButton(
              icon: const Icon(Icons.settings),
              onPressed: onSettingsPressed,
            ),
          ],
        ),
      ],
    );
  }
}
