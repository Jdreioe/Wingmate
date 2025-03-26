import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'dart:io' show Platform;

void showFullScreenText(BuildContext context, String text) {
  Navigator.push(
    context,
    kIsWeb 
        ? MaterialPageRoute(
            builder: (context) => FullScreenTextView(text: text),
          )
        : Platform.isIOS
            ? CupertinoPageRoute(
                builder: (context) => FullScreenTextView(text: text),
              )
            : MaterialPageRoute(
                builder: (context) => FullScreenTextView(text: text),
              ),
  );
}

class FullScreenTextView extends StatelessWidget {
  final String text;

  const FullScreenTextView({Key? key, required this.text}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    if (kIsWeb) {
      return _buildMaterialFullScreen(context);
    } else if (Platform.isIOS) {
      return _buildCupertinoFullScreen(context);
    } else {
      return _buildMaterialFullScreen(context);
    }
  }

  Widget _buildMaterialFullScreen(BuildContext context) {
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

  Widget _buildCupertinoFullScreen(BuildContext context) {
    return CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(
        middle: Text('Full Screen Text'),
      ),
      child: InteractiveViewer(
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
