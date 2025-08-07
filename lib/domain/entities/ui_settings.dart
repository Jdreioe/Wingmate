
import 'package:equatable/equatable.dart';
import 'package:flutter/material.dart';

class UiSettings extends Equatable {
  final int? id;
  final String name;
  final ThemeMode themeMode;
  final double fontSize;
  final double fieldSize;
  final double phraseFontSize;
  final double phraseWidth;
  final double phraseHeight;
  final String primaryLanguage;
  final String secondaryLanguage;

  const UiSettings({
    this.id,
    required this.name,
    this.themeMode = ThemeMode.system,
    this.fontSize = 20.0,
    this.fieldSize = 5.0,
    this.phraseFontSize = 14.0,
    this.phraseWidth = 100.0,
    this.phraseHeight = 50.0,
    this.primaryLanguage = 'en-US',
    this.secondaryLanguage = 'en-US',
  });

  @override
  List<Object?> get props => [
        id,
        name,
        themeMode,
        fontSize,
        fieldSize,
        phraseFontSize,
        phraseWidth,
        phraseHeight,
        primaryLanguage,
        secondaryLanguage,
      ];

  UiSettings copyWith({
    int? id,
    String? name,
    ThemeMode? themeMode,
    double? fontSize,
    double? fieldSize,
    double? phraseFontSize,
    double? phraseWidth,
    double? phraseHeight,
    String? primaryLanguage,
    String? secondaryLanguage,
  }) {
    return UiSettings(
      id: id ?? this.id,
      name: name ?? this.name,
      themeMode: themeMode ?? this.themeMode,
      fontSize: fontSize ?? this.fontSize,
      fieldSize: fieldSize ?? this.fieldSize,
      phraseFontSize: phraseFontSize ?? this.phraseFontSize,
      phraseWidth: phraseWidth ?? this.phraseWidth,
      phraseHeight: phraseHeight ?? this.phraseHeight,
      primaryLanguage: primaryLanguage ?? this.primaryLanguage,
      secondaryLanguage: secondaryLanguage ?? this.secondaryLanguage,
    );
  }
}
