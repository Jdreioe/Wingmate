import 'package:flutter/material.dart';
import 'package:wingmate/presentation/widgets/save_message_dialog.dart';

void showSaveMessageDialog(BuildContext context, String initialMessage, Function(String, String, bool) onSave) {
  showDialog(
    context: context,
    builder: (context) {
      return SaveMessageDialog(
        initialMessage: initialMessage,
        onSave: onSave,
      );
    },
  );
}
