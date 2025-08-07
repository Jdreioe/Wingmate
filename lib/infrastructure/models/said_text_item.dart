import 'package:wingmate/domain/entities/said_text.dart';

class SaidTextItem {
  int? id;
  int? date; // Store as integer (milliseconds since epoch)
  String? saidText;
  String? voiceName;
  double? pitch;
  double? speed;
  String? audioFilePath;
  int? createdAt;
  int? position; // new field to track item order
  String? primaryLanguage;
  SaidTextItem(
      {this.id,
      this.date,
      this.saidText,
      this.voiceName,
      this.pitch,
      this.speed,
      this.audioFilePath,
      this.createdAt,
      this.position,
      this.primaryLanguage});

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'date': date,
      'saidText': saidText,
      'voiceName': voiceName,
      'pitch': pitch,
      'speed': speed,
      'audioFilePath': audioFilePath,
      'createdAt': createdAt,
      'position': position,
      'primaryLanguage': primaryLanguage,
    };
  }

  static SaidTextItem fromMap(Map<String, dynamic> map) {
    return SaidTextItem(
      id: map['id'],
      date: map['date'],
      saidText: map['saidText'],
      voiceName: map['voiceName'],
      pitch: map['pitch']?.toDouble(), // Ensure correct type conversion
      speed: map['speed']?.toDouble(), // Ensure correct type conversion
      audioFilePath: map['audioFilePath'],
      createdAt: map['createdAt'],
      position: map['position'],
      primaryLanguage: map['primaryLanguage']
    );
  }

  SaidText toDomain() {
    return SaidText(
      id: id,
      date: date,
      saidText: saidText,
      voiceName: voiceName,
      pitch: pitch,
      speed: speed,
      audioFilePath: audioFilePath,
      createdAt: createdAt,
      position: position,
      primaryLanguage: primaryLanguage,
    );
  }

  static SaidTextItem fromDomain(SaidText saidText) {
    return SaidTextItem(
      id: saidText.id,
      date: saidText.date,
      saidText: saidText.saidText,
      voiceName: saidText.voiceName,
      pitch: saidText.pitch,
      speed: saidText.speed,
      audioFilePath: saidText.audioFilePath,
      createdAt: saidText.createdAt,
      position: saidText.position,
      primaryLanguage: saidText.primaryLanguage,
    );
  }
}
