import 'package:wingmate/domain/entities/voice.dart';

class VoiceItem {
  int? id;
  String? name;
  List<String>? supportedLanguages;
  String? gender;
  String? primaryLanguage;
  String? locale;
  int? createdAt;
  String? displayName;
  double? pitch;
  double? rate;
  String? pitchForSSML;
  String? rateForSSML;

  VoiceItem({
    this.id,
    this.name,
    this.supportedLanguages,
    this.gender,
    this.primaryLanguage,
    this.createdAt,
    this.displayName,
    this.locale,
    this.pitch,
    this.rate,
    this.pitchForSSML,
    this.rateForSSML,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'supportedLanguages': supportedLanguages?.join(','),
      'gender': gender,
      'locale': primaryLanguage,
      'createdAt': createdAt,
      'displayName': displayName,
      'pitch': pitch,
      'rate': rate,
      'pitchForSSML': pitchForSSML,
      'rateForSSML': rateForSSML,
    };
  }

  static VoiceItem fromMap(Map<String, dynamic> map) {
    return VoiceItem(
      id: map['id'],
      name: map['name'],
      supportedLanguages: map['supportedLanguages']?.split(','),
      gender: map['gender'],
      primaryLanguage: map['locale'],
      createdAt: map['createdAt'],
      displayName: map['displayName'],
      pitch: map['pitch'],
      rate: map['rate'],
      pitchForSSML: map['pitchForSSML'],
      rateForSSML: map['rateForSSML'],
    );
  }

  Voice toDomain() {
    return Voice(
      id: id,
      name: name,
      supportedLanguages: supportedLanguages,
      gender: gender,
      primaryLanguage: primaryLanguage,
      createdAt: createdAt,
      displayName: displayName, 
      selectedLanguage: '',
      pitch: pitch,
      rate: rate,
      pitchForSSML: pitchForSSML,
      rateForSSML: rateForSSML,
    );
  }

  static VoiceItem fromDomain(Voice voice) {
    return VoiceItem(
      id: voice.id,
      name: voice.name,
      supportedLanguages: voice.supportedLanguages,
      gender: voice.gender,
      primaryLanguage: voice.primaryLanguage,
      createdAt: voice.createdAt,
      displayName: voice.displayName,
      pitch: voice.pitch,
      rate: voice.rate,
      pitchForSSML: voice.pitchForSSML,
      rateForSSML: voice.rateForSSML,
    );
  }
}
