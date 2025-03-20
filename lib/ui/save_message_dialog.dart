import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart'; // Import for Cupertino widgets
import 'dart:io';

class SaveMessageDialog extends StatefulWidget {
  final Function(String message, String category, bool categoryChecked) onSave;
  final String initialMessage;

  const SaveMessageDialog(
      {Key? key, required this.onSave, this.initialMessage = ''})
      : super(key: key);

  @override
  _SaveMessageDialogState createState() => _SaveMessageDialogState();
}

class _SaveMessageDialogState extends State<SaveMessageDialog> {
  late TextEditingController _messageController;
  final TextEditingController _categoryController = TextEditingController();
  bool _isCategoryChecked = false;

  @override
  void initState() {
    super.initState();
    _messageController =
        TextEditingController(text: widget.initialMessage);
  }

  @override
  Widget build(BuildContext context) {
    if (Platform.isIOS) {
      return CupertinoAlertDialog(
        title: const Text('Save Message'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CupertinoTextField(
              controller: _messageController,
              placeholder: 'Message',
            ),
            CupertinoTextField(
              controller: _categoryController,
              placeholder: 'Category',
            ),
            Row(
              children: [
                CupertinoCheckbox(
                  value: _isCategoryChecked,
                  onChanged: (bool? value) {
                    setState(() {
                      _isCategoryChecked = value ?? false;
                    });
                  },
                ),
                const Text('Category'),
              ],
            ),
          ],
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () {
              Navigator.of(context).pop();
            },
            child: const Text('Cancel'),
          ),
          CupertinoDialogAction(
            onPressed: () {
              widget.onSave(
                _messageController.text,
                _categoryController.text,
                _isCategoryChecked,
              );
              Navigator.of(context).pop();
            },
            child: const Text('Save'),
          ),
        ],
      );
    } else {
      return AlertDialog(
        title: const Text('Save Message'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _messageController,
              decoration: const InputDecoration(labelText: 'Message'),
            ),
            TextField(
              controller: _categoryController,
              decoration: const InputDecoration(labelText: 'Category'),
            ),
            Row(
              children: [
                Checkbox(
                  value: _isCategoryChecked,
                  onChanged: (bool? value) {
                    setState(() {
                      _isCategoryChecked = value ?? false;
                    });
                  },
                ),
                const Text('Category'),
              ],
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
            },
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              widget.onSave(
                _messageController.text,
                _categoryController.text,
                _isCategoryChecked,
              );
              Navigator.of(context).pop();
            },
            child: const Text('Save'),
          ),
        ],
      );
    }
  }

  @override
  void dispose() {
    _messageController.dispose();
    _categoryController.dispose();
    super.dispose();
  }
}

