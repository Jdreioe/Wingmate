
import 'package:equatable/equatable.dart';

class SaidText extends Equatable {
  final int? id;
  final int? date;
  final String? saidText;
  final String? voiceName;
  final double? pitch;
  final double? speed;
  final String? audioFilePath;
  final int? createdAt;
  final int? position;
  final String? primaryLanguage;

  const SaidText({
    this.id,
    this.date,
    this.saidText,
    this.voiceName,
    this.pitch,
    this.speed,
    this.audioFilePath,
    this.createdAt,
    this.position,
    this.primaryLanguage,
  });

  @override
  List<Object?> get props => [
        id,
        date,
        saidText,
        voiceName,
        pitch,
        speed,
        audioFilePath,
        createdAt,
        position,
        primaryLanguage,
      ];
}
