
import 'package:equatable/equatable.dart';

class Phrase extends Equatable {
  final int? id;
  final String? name;
  final String? text;
  final bool? isCategory;
  final int? parentId;
  final int? createdAt;
  final int? position;
  final String? filePath;
  final String? voiceName;
  final double? pitch;
  final String? selectedLanguage;
  final double? rateForSsml;
  final double? pitchForSsml;
  final String? imagePath;
  final String? backgroundColor;
  final String? labelColor;

  const Phrase({
    this.id,
    this.name,
    this.text,
    this.isCategory,
    this.parentId,
    this.createdAt,
    this.position,
    this.filePath,
    this.voiceName,
    this.pitch,
    this.selectedLanguage,
    this.rateForSsml,
    this.pitchForSsml,
    this.imagePath,
    this.backgroundColor,
    this.labelColor,
  });

  Phrase copyWith({
    int? id,
    String? name,
    String? text,
    bool? isCategory,
    int? parentId,
    int? createdAt,
    int? position,
    String? filePath,
    String? voiceName,
    double? pitch,
    String? selectedLanguage,
    double? rateForSsml,
    double? pitchForSsml,
    String? imagePath,
    String? backgroundColor,
    String? labelColor,
  }) {
    return Phrase(
      id: id ?? this.id,
      name: name ?? this.name,
      text: text ?? this.text,
      isCategory: isCategory ?? this.isCategory,
      parentId: parentId ?? this.parentId,
      createdAt: createdAt ?? this.createdAt,
      position: position ?? this.position,
      filePath: filePath ?? this.filePath,
      voiceName: voiceName ?? this.voiceName,
      pitch: pitch ?? this.pitch,
      selectedLanguage: selectedLanguage ?? this.selectedLanguage,
      rateForSsml: rateForSsml ?? this.rateForSsml,
      pitchForSsml: pitchForSsml ?? this.pitchForSsml,
      imagePath: imagePath ?? this.imagePath,
      backgroundColor: backgroundColor ?? this.backgroundColor,
      labelColor: labelColor ?? this.labelColor,
    );
  }

  @override
  List<Object?> get props => [
        id,
        name,
        text,
        isCategory,
        parentId,
        createdAt,
        position,
        filePath,
        voiceName,
        pitch,
        selectedLanguage,
        rateForSsml,
        pitchForSsml,
        imagePath,
        backgroundColor,
        labelColor,
      ];
}
