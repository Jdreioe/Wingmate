import 'package:flutter/material.dart';

class SaveMessageDialog extends StatefulWidget {
  final Function(String message, String category) onSave;

  const SaveMessageDialog({Key? key, required this.onSave}) : super(key: key);

  @override
  _SaveMessageDialogState createState() => _SaveMessageDialogState();
}

class _SaveMessageDialogState extends State<SaveMessageDialog> {
  final TextEditingController _messageController = TextEditingController();
  final TextEditingController _categoryController = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Save Message'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _messageController,
            decoration: InputDecoration(labelText: 'Message'),
          ),
          TextField(
            controller: _categoryController,
            decoration: InputDecoration(labelText: 'Category'),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
          },
          child: Text('Cancel'),
        ),
        TextButton(
          onPressed: () {
            widget.onSave(
              _messageController.text,
              _categoryController.text,
            );
            Navigator.of(context).pop();
          },
          child: Text('Save'),
        ),
      ],
    );
  }
}
