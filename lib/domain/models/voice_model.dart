import 'package:wingmate/infrastructure/models/voice_item.dart';
import 'package:wingmate/domain/entities/voice.dart' as domain_entities;

class Voice {
  domain_entities.Voice toDomain() {
    return domain_entities.Voice(
      id: null,
      name: name,
      supportedLanguages: supportedLanguages,
      gender: null,
      primaryLanguage: primaryLanguage,
      createdAt: null,
      displayName: null,
      selectedLanguage: primaryLanguage,
      pitch: pitch,
      rate: rate,
      pitchForSSML: pitchForSSML,
      rateForSSML: rateForSSML,
    );
  }
  // Represents a voice configuration, including language options and playback tuning.
  final String name;

  final List<String> supportedLanguages;

  final String primaryLanguage;

  final double pitch;

  final double rate;
  final String pitchForSSML;
  final String rateForSSML;
  Voice(
      {required this.name,
      required this.supportedLanguages,
      required this.primaryLanguage,
      required this.pitch,
      required this.rate,
      required this.pitchForSSML,
      required this.rateForSSML});

  factory Voice.fromVoiceItem(VoiceItem item) {
    return Voice(
      name: item.name ?? '',
      supportedLanguages: item.supportedLanguages ?? [],
      primaryLanguage: item.primaryLanguage ?? '',
      pitch: 0.0,
      rate: 0.0,
      pitchForSSML: 'medium',
      rateForSSML: 'medium',
    );
  }

  static Voice fromDomain(domain_entities.Voice voice) {
    return Voice(
      name: voice.name ?? '',
      supportedLanguages: voice.supportedLanguages ?? [],
      primaryLanguage: voice.primaryLanguage ?? '',
      pitch: voice.pitch ?? 0.0,
      rate: voice.rate ?? 0.0,
      pitchForSSML: voice.pitchForSSML ?? 'medium',
      rateForSSML: voice.rateForSSML ?? 'medium',
    );
  }
}
