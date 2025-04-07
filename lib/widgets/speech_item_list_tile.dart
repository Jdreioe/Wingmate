import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;
import 'package:wingmate/utils/speech_item.dart';

class SpeechItemListTile extends StatelessWidget {
  final dynamic item;
  final VoidCallback? onTap;
  final bool isCupertino;

  const SpeechItemListTile({
    Key? key,
    required this.item,
    this.onTap,
    this.isCupertino = false,
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
    if (isCupertino) {
      return CupertinoListTile(
        key: ValueKey(item),
        leading: const Icon(CupertinoIcons.folder),
        title: Text(item),
        onTap: onTap,
      );
    } else {
      return ListTile(
        key: ValueKey(item),
        leading: const Icon(Icons.folder),
        title: Text(item),
        onTap: onTap,
      );
    }
  }

  Widget _buildSpeechItemTile(SpeechItem speechItem) {
    if (isCupertino) {
      return CupertinoListTile(
        key: ValueKey(speechItem),
        leading: Icon(speechItem.isFolder!
            ? CupertinoIcons.folder
            : CupertinoIcons.speaker_2),
        title: Text(speechItem.name ?? ''),
        subtitle: speechItem.isFolder! ? null : Text(speechItem.text ?? ''),
        onTap: onTap,
      );
    } else {
      return ListTile(
        key: ValueKey(speechItem),
        leading: Icon(speechItem.isFolder! ? Icons.folder : Icons.speaker_phone),
        title: Text(speechItem.name ?? ''),
        subtitle: speechItem.isFolder! ? null : Text(speechItem.text ?? ''),
        onTap: onTap,
      );
    }
  }
} 