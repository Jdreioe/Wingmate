import 'package:flutter/material.dart';

class UiSettings {
  int? id;
  String name;
  ThemeMode themeMode;
  double fontSize;
  double fieldSize;
  double phraseFontSize;
  double phraseWidth;
  double phraseHeight;


  UiSettings({
    this.id,
    required this.name,
    this.themeMode = ThemeMode.system,
    this.fontSize = 20.0,
    this.fieldSize = 5.0,
    this.phraseFontSize = 14.0,
    this.phraseWidth = 100.0,
    this.phraseHeight = 50.0,
  });

  // Convert a UiSettings object into a Map. The keys must correspond to the names of the
  // columns in the database.
  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'themeMode': themeMode.toString(),
      'fontSize': fontSize,
      'fieldSize': fieldSize,
      'phraseFontSize': phraseFontSize,
      'phraseWidth': phraseWidth,
      'phraseHeight': phraseHeight,
    };
  }

  // Create a UiSettings object from a Map
  factory UiSettings.fromMap(Map<String, dynamic> map) {
    return UiSettings(
      id: map['id'],
      name: map['name'],
      themeMode: ThemeMode.values.firstWhere(
            (e) => e.toString() == map['themeMode'],
        orElse: () => ThemeMode.system,
      ),
      fontSize: map['fontSize'],
      fieldSize: map['fieldSize'],
      phraseFontSize: map['phraseFontSize'],
      phraseWidth: map['phraseWidth'],
      phraseHeight: map['phraseHeight'],
    );
  }
}