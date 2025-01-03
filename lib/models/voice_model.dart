import 'package:hive/hive.dart';

part 'voice_model.g.dart';

@HiveType(typeId: 1)
class Voice {
  // Represents a voice configuration, including language options and playback tuning.
  @HiveField(0)
  final String name;

  @HiveField(1)
  final List<String> supportedLanguages;

  @HiveField(2)
  final String selectedLanguage;

  @HiveField(3)
  final double pitch;

  @HiveField(4)
  final double rate;
  Voice(
      {required this.name,
      required this.supportedLanguages,
      required this.selectedLanguage,
      required this.pitch,
      required this.rate});
}
