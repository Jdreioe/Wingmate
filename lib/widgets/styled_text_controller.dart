import 'package:flutter/material.dart';

class StyledTextController extends TextEditingController {
  final Color highlightColor;
  final RegExp langTagRegex = RegExp(
    r'''<lang(?: xml:lang="([^"]+)")?>((?:.|
|)*?)<\/lang>''',
    caseSensitive: false,
  );

  // Mapping for language codes to user-friendly representations (emojis or short codes)
  final Map<String, String> _languageDisplayMap = {
    'en-US': '🇺🇸', // US Flag
    'es-ES': '🇪🇸', // Spain Flag
    'da-DK': '🇩🇰', // Denmark Flag
    'fr-FR': '🇫🇷', // France Flag
    // Add more as needed
  };

  StyledTextController({String? text, required this.highlightColor}) : super(text: text);

  @override
  TextSpan buildTextSpan({required BuildContext context, TextStyle? style, required bool withComposing}) {
    final List<TextSpan> spans = [];
    final String currentText = text;

    int lastMatchEnd = 0;
    for (final Match match in langTagRegex.allMatches(currentText)) {
      // Add text before the current match
      if (match.start > lastMatchEnd) {
        spans.add(TextSpan(text: currentText.substring(lastMatchEnd, match.start), style: style));
      }
      
      final String? langCode = match.group(1); // The language code (e.g., "en-US")
      final String? content = match.group(2); // The content inside the <lang> tag

      // Determine the display string for the language tag
      final String displayLang = _languageDisplayMap[langCode?.toLowerCase()] ?? '[${langCode ?? 'LANG'}]';

      // If content is null, it means the tag is malformed or incomplete. Render the raw matched text.
      if (content == null) {
        spans.add(TextSpan(text: match.group(0), style: style));
      } else {
        // Render the pretty version of the tag and its content
        spans.add(
          TextSpan(
            children: [
              TextSpan(
                text: '$displayLang ', // Display the emoji/code before the content
                style: style?.copyWith(
                  color: highlightColor.withOpacity(0.7), // Slightly less prominent for the tag
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
            ],
          ),
        );
      }
      lastMatchEnd = match.end;
    }

    // Add any remaining text after the last match
    if (lastMatchEnd < currentText.length) {
      spans.add(TextSpan(text: currentText.substring(lastMatchEnd), style: style));
    }

    return TextSpan(style: style, children: spans);
  }
}