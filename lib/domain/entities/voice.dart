
import 'package:equatable/equatable.dart';
import 'package:hive/hive.dart';

part 'voice.g.dart';

@HiveType(typeId: 1)
class Voice extends Equatable {
  final int? id;
  final String? name;
  final List<String>? supportedLanguages;
  final String? gender;
  final String? primaryLanguage;
  final int? createdAt;
  final String? displayName;
  final String selectedLanguage;
  final double? pitch;
  final double? rate;
  final String? pitchForSSML;
  final String? rateForSSML;

  const Voice({
    this.id,
    this.name,
    this.supportedLanguages,
    this.gender,
    this.primaryLanguage,
    this.createdAt,
    this.displayName,
    required this.selectedLanguage,
    this.pitch,
    this.rate,
    this.pitchForSSML,
    this.rateForSSML,
  });

  factory Voice.fromMap(Map<String, dynamic> map) {
    return Voice(
      id: map['id'] as int?,
      name: map['name'] as String?,
      supportedLanguages: (map['supportedLanguages'] as List<dynamic>?)?.map((e) => e as String).toList(),
      gender: map['gender'] as String?,
      primaryLanguage: map['primaryLanguage'] as String?,
      createdAt: map['createdAt'] as int?,
      displayName: map['displayName'] as String?,
      selectedLanguage: map['selectedLanguage'] as String? ?? '', // Provide a default empty string if null
      pitch: map['pitch'] as double?,
      rate: map['rate'] as double?,
      pitchForSSML: map['pitchForSSML'] as String?,
      rateForSSML: map['rateForSSML'] as String?,
    );
  }

  Voice copyWith({
    int? id,
    String? name,
    List<String>? supportedLanguages,
    String? gender,
    String? primaryLanguage,
    int? createdAt,
    String? displayName,
    String? selectedLanguage,
    double? pitch,
    double? rate,
    String? pitchForSSML,
    String? rateForSSML,
  }) {
    return Voice(
      id: id ?? this.id,
      name: name ?? this.name,
      supportedLanguages: supportedLanguages ?? this.supportedLanguages,
      gender: gender ?? this.gender,
      primaryLanguage: primaryLanguage ?? this.primaryLanguage,
      createdAt: createdAt ?? this.createdAt,
      displayName: displayName ?? this.displayName,
      selectedLanguage: selectedLanguage ?? this.selectedLanguage,
      pitch: pitch ?? this.pitch,
      rate: rate ?? this.rate,
      pitchForSSML: pitchForSSML ?? this.pitchForSSML,
      rateForSSML: rateForSSML ?? this.rateForSSML,
    );
  }

  @override
  List<Object?> get props => [
        id,
        name,
        supportedLanguages,
        gender,
        primaryLanguage,
        createdAt,
        displayName,
        selectedLanguage,
        pitch,
        rate,
        pitchForSSML,
        rateForSSML,
      ];
}
