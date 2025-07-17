import 'package:flutter/cupertino.dart';
// Removed material.dart as it is no longer used
import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;

class MessageInputRow extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onClear;
  final VoidCallback onPlayPause;
  final bool isPlaying;

  // The isCupertino parameter was removed as the entire app is now Cupertino
  const MessageInputRow({
    Key? key,
    required this.controller,
    required this.focusNode,
    required this.onClear,
    required this.onPlayPause,
    required this.isPlaying,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16.0, left: 16.0, right: 16.0),
      child: Row(
        children: [
          _buildClearButton(),
          Expanded(
            child: _buildTextField(),
          ),
          _buildPlayPauseButton(),
        ],
      ),
    );
  }

  Widget _buildClearButton() {
    // Consolidated to a single Cupertino widget
    return CupertinoButton(
      onPressed: onClear,
      child: const Icon(CupertinoIcons.delete),
    );
  }

  Widget _buildTextField() {
    // Consolidated to a single Cupertino widget
    return CupertinoTextField(
      controller: controller,
      focusNode: focusNode,
      minLines: 1,
      maxLines: 5,
      placeholder: 'Enter text',
      decoration: BoxDecoration(
        border: Border.all(color: CupertinoColors.lightBackgroundGray),
        borderRadius: BorderRadius.circular(5.0),
      ),
      textInputAction: TextInputAction.done,
      keyboardType: TextInputType.text,
    );
  }

  Widget _buildPlayPauseButton() {
    // Consolidated to a single Cupertino widget
    return CupertinoButton(
      onPressed: onPlayPause,
      child: Icon(isPlaying ? CupertinoIcons.pause : CupertinoIcons.play_arrow),
    );
  }
}