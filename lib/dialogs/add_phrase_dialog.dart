import 'package:flutter/material.dart';
import 'package:wingmate/data/phrase_item.dart';
import 'package:wingmate/data/category_item.dart';

class AddPhraseDialog extends StatefulWidget {
  final Function(PhraseItem) onSave;
  final List<CategoryItem> categories; // To populate the category dropdown
  final PhraseItem? phraseItem; // Optional: for editing existing phrase

  const AddPhraseDialog({
    Key? key,
    required this.onSave,
    required this.categories,
    this.phraseItem, // Make it optional
  }) : super(key: key);

  @override
  _AddPhraseDialogState createState() => _AddPhraseDialogState();
}

class _AddPhraseDialogState extends State<AddPhraseDialog> {
  final _phraseTextController = TextEditingController();
  final _altTextController = TextEditingController();
  Color _selectedColor = Colors.blue; // Default color
  CategoryItem? _selectedCategory; // Default category

  @override
  void initState() {
    super.initState();
    if (widget.phraseItem != null) {
      _phraseTextController.text = widget.phraseItem!.text ?? '';
      _altTextController.text = widget.phraseItem!.name ?? '';
      _selectedColor = widget.phraseItem!.backgroundColor != null
          ? Color(int.parse(widget.phraseItem!.backgroundColor!, radix: 16) | 0xFF000000)
          : Colors.blue;
      try {
        _selectedCategory = widget.categories.firstWhere(
          (cat) => cat.id == widget.phraseItem!.parentId,
        );
      } catch (e) {
        _selectedCategory = null; // Category not found
      }
    } else if (widget.categories.isNotEmpty) {
      _selectedCategory = widget.categories.first;
    }
  }

  @override
  void dispose() {
    _phraseTextController.dispose();
    _altTextController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.phraseItem == null ? 'Add New Phrase' : 'Edit Phrase'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _phraseTextController,
              decoration: const InputDecoration(labelText: 'New phrase text'),
            ),
            TextField(
              controller: _altTextController,
              decoration: const InputDecoration(labelText: 'Alternative text/emoji'),
            ),
            // Color picker
            ListTile(
              title: const Text('Color'),
              trailing: Container(
                width: 30,
                height: 30,
                color: _selectedColor,
              ),
              onTap: _pickColor, // Implement this method
            ),
            // Category dropdown
            DropdownButtonFormField<CategoryItem>(
              value: _selectedCategory,
              decoration: const InputDecoration(labelText: 'Belongs to category'),
              items: widget.categories.map((category) {
                return DropdownMenuItem(
                  value: category,
                  child: Text(category.name ?? 'No Name'),
                );
              }).toList(),
              onChanged: (CategoryItem? newValue) {
                setState(() {
                  _selectedCategory = newValue;
                });
              },
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.pop(context); // Cancel
          },
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () {
            _savePhrase(); // Implement this method
          },
          child: const Text('Save'),
        ),
      ],
    );
  }

  void _pickColor() {
    // This will be a simple color picker for now.
    // You might want to use a package like 'flutter_colorpicker' for a more advanced one.
    showDialog(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: const Text('Select Color'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                leading: const CircleAvatar(backgroundColor: Colors.red),
                title: const Text('Red'),
                onTap: () {
                  setState(() {
                    _selectedColor = Colors.red;
                  });
                  Navigator.pop(context);
                },
              ),
              ListTile(
                leading: const CircleAvatar(backgroundColor: Colors.blue),
                title: const Text('Blue'),
                onTap: () {
                  setState(() {
                    _selectedColor = Colors.blue;
                  });
                  Navigator.pop(context);
                },
              ),
              ListTile(
                leading: const CircleAvatar(backgroundColor: Colors.green),
                title: const Text('Green'),
                onTap: () {
                  setState(() {
                    _selectedColor = Colors.green;
                  });
                  Navigator.pop(context);
                },
              ),
              // Add more colors as needed
            ],
          ),
        );
      },
    );
  }

  void _savePhrase() {
    final phraseToSave = PhraseItem(
      id: widget.phraseItem?.id, // Preserve ID if editing
      text: _phraseTextController.text.trim(),
      name: _altTextController.text.trim(),
      backgroundColor: _selectedColor.value.toRadixString(16), // Store color as hex string
      parentId: _selectedCategory?.id,
      isCategory: false, // This is a phrase, not a category
      createdAt: widget.phraseItem?.createdAt ?? DateTime.now().millisecondsSinceEpoch, // Preserve createdAt if editing
    );
    widget.onSave(phraseToSave);
    Navigator.pop(context); // Close dialog
  }
}
