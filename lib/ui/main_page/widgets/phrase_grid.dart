import 'package:flutter/material.dart';
import 'package:wingmate/data/ui_settings.dart';
import 'package:wingmate/data/phrase_item.dart';

class PhraseGrid extends StatelessWidget {
  final void Function(String) onPhraseSelected;
  final void Function(PhraseItem) onPhraseLongPressed; // New callback
  final UiSettings uiSettings;
  final List<PhraseItem> phraseItems; // New: List of PhraseItem

  const PhraseGrid({
    Key? key,
    required this.onPhraseSelected,
    required this.onPhraseLongPressed, // New required parameter
    required this.uiSettings,
    required this.phraseItems, // New: Required parameter
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    debugPrint('PhraseGrid building with ${phraseItems.length} items.');
    return GridView.builder(
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        crossAxisSpacing: 4,
        mainAxisSpacing: 4,
        mainAxisExtent: uiSettings.phraseHeight, // Set fixed height
      ),
      itemCount: phraseItems.length,
      itemBuilder: (context, index) {
        return _buildPhraseButton(phraseItems[index]);
      },
    );
  }

  Widget _buildPhraseButton(PhraseItem item) {
    if (item.id == -1) { // Check for the special add button
      return ElevatedButton(
        onPressed: () => onPhraseSelected('ADD_PHRASE_BUTTON'), // Use a special string to identify
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.grey[300], // Light grey background
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(8),
          ),
        ),
        child: const Icon(Icons.add, size: 48, color: Colors.black54), // Plus icon
      );
    }

    final Color? bgColor = item.backgroundColor != null
        ? Color(int.parse(item.backgroundColor!,
        radix: 16) | 0xFF000000) // Parse hex and ensure opacity
        : null; // Use default if null

    return ElevatedButton(
      onPressed: () => onPhraseSelected(item.text ?? ''),
      onLongPress: () => onPhraseLongPressed(item), // New: Long press to edit
      style: ElevatedButton.styleFrom(
        backgroundColor: bgColor,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
        ),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (item.name != null &&
              item.name!.isNotEmpty) // Use name for alt text/emoji
            Text(
              item.name!,
              textAlign: TextAlign.center,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: uiSettings.phraseFontSize *
                  0.7), // Smaller for alt text
            ),
          Text(
            item.text ?? '',
            textAlign: TextAlign.center,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: uiSettings.phraseFontSize),
          ),
        ],
      ),
    );
  }
}