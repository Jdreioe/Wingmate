import 'package:flutter/material.dart';

class AddBreakDialog extends StatefulWidget {
  const AddBreakDialog({Key? key}) : super(key: key);

  @override
  _AddBreakDialogState createState() => _AddBreakDialogState();
}

class _AddBreakDialogState extends State<AddBreakDialog> {
  final _formKey = GlobalKey<FormState>();
  int _breakTime = 500;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add Break'),
      content: Form(
        key: _formKey,
        child: TextFormField(
          initialValue: _breakTime.toString(),
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            labelText: 'Break time in milliseconds',
          ),
          validator: (value) {
            if (value == null || value.isEmpty) {
              return 'Please enter a value';
            }
            final n = int.tryParse(value);
            if (n == null) {
              return 'Please enter a valid number';
            }
            return null;
          },
          onSaved: (value) {
            _breakTime = int.parse(value!);
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () {
            if (_formKey.currentState!.validate()) {
              _formKey.currentState!.save();
              Navigator.pop(context, _breakTime);
            }
          },
          child: const Text('Add'),
        ),
      ],
    );
  }
}
