import 'package:hive/hive.dart';

part 'voice_model.g.dart';

@HiveType(typeId: 1)
class Voice {
  @HiveField(0)
  final String name;

  @HiveField(1)
  final List<String> supportedLanguages;

  Voice({required this.name, required this.supportedLanguages});
}
