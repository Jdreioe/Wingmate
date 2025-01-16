import 'package:flutter/material.dart';

class SaveMessageDialog extends StatefulWidget {
  final Function(String message, String category, bool categoryChecked) onSave;
  final String initialMessage;

  const SaveMessageDialog({Key? key, required this.onSave, this.initialMessage = ''}) : super(key: key);

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
    _messageController = TextEditingController(text: widget.initialMessage);
  }

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
              Text('Category'),
            ],
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
              _isCategoryChecked
            );
            Navigator.of(context).pop();
          },
          child: Text('Save'),
        ),
      ],
    );
  }
}
