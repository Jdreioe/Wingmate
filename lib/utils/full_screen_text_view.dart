import 'package:flutter/material.dart';

void showFullScreenText(BuildContext context, String text) {
  Navigator.push(
    context,
    MaterialPageRoute(
      builder: (context) => FullScreenTextView(text: text),
    ),
  );
}

class FullScreenTextView extends StatelessWidget {
  final String text;

  const FullScreenTextView({Key? key, required this.text}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Full Screen Text'),
      ),
      body: InteractiveViewer(
        panEnabled: true,
        scaleEnabled: true,
        child: LayoutBuilder(
          builder: (context, constraints) {
            return SingleChildScrollView(
              padding: const EdgeInsets.all(16.0),
              child: Text(
                text,
                style: TextStyle(fontSize: constraints.maxWidth / 10), // Adjust the font size based on the width
              ),
            );
          },
        ),
      ),
    );
  }
}
