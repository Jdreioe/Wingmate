import 'package:flutter/cupertino.dart';
// Removed material.dart as it is no longer needed
// Removed foundation.dart as it is no longer needed
import 'package:wingmate/utils/speech_item.dart';

class SpeechItemListTile extends StatelessWidget {
  final dynamic item;
  final VoidCallback? onTap;

  // The isCupertino parameter was removed as the entire app is now Cupertino
  const SpeechItemListTile({
    Key? key,
    required this.item,
    this.onTap,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    if (item is String && item == 'History') {
      return _buildHistoryTile();
    } else if (item is SpeechItem) {
      return _buildSpeechItemTile(item as SpeechItem);
    }
    return Container();
  }

  Widget _buildHistoryTile() {
    // Consolidated to a single Cupertino widget
    return CupertinoListTile(
      key: ValueKey(item),
      leading: const Icon(CupertinoIcons.folder),
      title: Text(item),
      onTap: onTap,
    );
  }

  Widget _buildSpeechItemTile(SpeechItem speechItem) {
    // Consolidated to a single Cupertino widget
    return CupertinoListTile(
      key: ValueKey(speechItem),
      leading: Icon(speechItem.isFolder!
          ? CupertinoIcons.folder
          : CupertinoIcons.speaker_2),
      title: Text(speechItem.name ?? ''),
      subtitle: speechItem.isFolder! ? null : Text(speechItem.text ?? ''),
      onTap: onTap,
    );
  }
}