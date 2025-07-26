import 'package:flutter/material.dart';

class TopBar extends StatelessWidget {
  final VoidCallback onWrapWithLangTag;
  final VoidCallback onHistoryPressed;
  final VoidCallback onSettingsPressed;
  final Widget secondaryLanguageSelector;

  const TopBar({
    Key? key,
    required this.onWrapWithLangTag,
    required this.onHistoryPressed,
    required this.onSettingsPressed,
    required this.secondaryLanguageSelector,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        DropdownButton<String>(
          value: 'Danish',
          items: <String>['Danish', 'English', 'Spanish']
              .map<DropdownMenuItem<String>>((String value) {
            return DropdownMenuItem<String>(
              value: value,
              child: Text(value),
            );
          }).toList(),
          onChanged: (String? newValue) {},
        ),
        Row(
          children: [
            secondaryLanguageSelector,
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