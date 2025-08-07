import 'package:flutter/material.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

class StyledTextController extends TextEditingController {
  final Color highlightColor;
  final UiSettings uiSettings;
  final RegExp langTagRegex = RegExp(
    r'''\[([a-zA-Z]{2}-[a-zA-Z]{2})\](.*?)\[/\1\]|\[BREAK:(\d+)\]''',
    caseSensitive: false,
  );

  StyledTextController({String? text, required this.highlightColor, required this.uiSettings}) : super(text: text);

  String get ssmlText {
    String result = text.replaceAllMapped(
      RegExp(r'\[BREAK:(\d+)\]'),
      (match) => '<break time="${match.group(1)}ms" />',
    );
    result = result.replaceAllMapped(
      RegExp(r'\[([a-zA-Z]{2}-[a-zA-Z]{2})\](.*?)\[/\1\]'),
      (match) => '<lang xml:lang="${match.group(1)}">${match.group(2)}</lang>',
    );
    return result;
  }

  @override
  TextSpan buildTextSpan({required BuildContext context, TextStyle? style, required bool withComposing}) {
    final List<InlineSpan> children = [];
    text.splitMapJoin(langTagRegex, onMatch: (Match match) {
      if (match.group(3) != null) {
        // It's a break tag
        children.add(
          TextSpan(
            text: match.group(0),
            style: style?.copyWith(color: Colors.grey),
          ),
        );
      } else if (match.group(1) != null && match.group(2) != null) {
        // It's a lang tag
        final String langCode = match.group(1)!;
        final String content = match.group(2)!;
        children.add(
          TextSpan(
            children: [
              TextSpan(
                text: '[$langCode]',
                style: style?.copyWith(
                  color: highlightColor.withOpacity(0.7),
                  fontWeight: FontWeight.normal,
                ),
              ),
              TextSpan(
                text: content,
                style: style?.copyWith(
                  color: highlightColor,
                  fontWeight: FontWeight.bold,
                ),
              ),
              TextSpan(
                text: '[/]',
                style: style?.copyWith(
                  color: highlightColor.withOpacity(0.7),
                  fontWeight: FontWeight.normal,
                ),
              ),
            ],
          ),
        );
      } else {
        // Fallback for any unexpected matches
        children.add(TextSpan(text: match.group(0), style: style));
      }
      return ''; // Return empty string for matched part
    }, onNonMatch: (String nonMatch) {
      children.add(TextSpan(text: nonMatch, style: style));
      return ''; // Return empty string for non-matched part
    });

    return TextSpan(style: style, children: children);
  }
}