import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;

class MessageInputRow extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onClear;
  final VoidCallback onPlayPause;
  final bool isPlaying;
  final bool isCupertino;

  const MessageInputRow({
    Key? key,
    required this.controller,
    required this.focusNode,
    required this.onClear,
    required this.onPlayPause,
    required this.isPlaying,
    this.isCupertino = false,
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
    if (isCupertino) {
      return CupertinoButton(
        onPressed: onClear,
        child: const Icon(CupertinoIcons.delete),
      );
    } else {
      return IconButton(
        icon: const Icon(Icons.delete),
        onPressed: onClear,
      );
    }
  }

  Widget _buildTextField() {
    if (isCupertino) {
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
    } else {
      return TextField(
        controller: controller,
        focusNode: focusNode,
        minLines: 1,
        maxLines: 5,
        decoration: InputDecoration(
          labelText: 'Enter text',
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(5.0),
          ),
          focusedBorder: const OutlineInputBorder(
            borderSide: BorderSide(color: Colors.blue, width: 2.0),
          ),
          enabledBorder: const OutlineInputBorder(
            borderSide: BorderSide(color: Colors.grey, width: 1.0),
          ),
        ),
        textInputAction: TextInputAction.done,
        keyboardType: TextInputType.text,
      );
    }
  }

  Widget _buildPlayPauseButton() {
    if (isCupertino) {
      return CupertinoButton(
        onPressed: onPlayPause,
        child: Icon(isPlaying ? CupertinoIcons.pause : CupertinoIcons.play_arrow),
      );
    } else {
      return IconButton(
        icon: Icon(isPlaying ? Icons.pause : Icons.play_arrow),
        onPressed: onPlayPause,
      );
    }
  }
} 