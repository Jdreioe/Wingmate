import 'package:flutter/material.dart';

class PlaybackControls extends StatelessWidget {
  final bool isExpanded;
  final VoidCallback onPlayPressed;
  final VoidCallback onStopPressed;
  final VoidCallback onReplayPressed;
  final VoidCallback onToggleExpand;
  final bool isTextEmpty;
  final bool isLastUsedEmpty;

  const PlaybackControls({
    Key? key,
    required this.isExpanded,
    required this.onPlayPressed,
    required this.onStopPressed,
    required this.onReplayPressed,
    required this.onToggleExpand,
    required this.isTextEmpty,
    required this.isLastUsedEmpty,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        IconButton.filledTonal(
          onPressed: onPlayPressed,
          icon: const Icon(Icons.play_arrow),
          iconSize: 40,
          padding: const EdgeInsets.all(12.0)
        ),
        IconButton.filledTonal(
          onPressed: onStopPressed,
          icon: const Icon(Icons.stop),
          iconSize: 40,
            padding: const EdgeInsets.all(12.0)
        ),
        IconButton.filledTonal(
          onPressed: onReplayPressed,
          icon: Icon(isTextEmpty && !isLastUsedEmpty ? Icons.redo : Icons.clear),
          iconSize: 40,
          padding: const EdgeInsets.all(12.0),

    ),
        IconButton.filledTonal(
          onPressed: onToggleExpand,
          icon: Icon(isExpanded ? Icons.unfold_less : Icons.unfold_more),
          iconSize: 40,
          padding: const EdgeInsets.all(12.0)
        ),
      ],
    );
  }
}
