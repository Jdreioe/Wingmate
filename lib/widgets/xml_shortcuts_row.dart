import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;

class XmlShortcutsRow extends StatelessWidget {
  final Function(String) onAddTag;
  final bool isCupertino;

  const XmlShortcutsRow({
    Key? key,
    required this.onAddTag,
    this.isCupertino = false,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(8.0),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            _buildButton(
              label: 'English',
              onPressed: () => onAddTag('<lang xml:lang="en-US"> </lang>'),
            ),
            const SizedBox(width: 8),
            _buildButton(
              label: '2 sec break',
              onPressed: () => onAddTag('<break time="2s"/>'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildButton({required String label, required VoidCallback onPressed}) {
    if (isCupertino) {
      return CupertinoButton.filled(
        onPressed: onPressed,
        child: Text(label),
      );
    } else {
      return ElevatedButton(
        onPressed: onPressed,
        child: Text(label),
      );
    }
  }
} 