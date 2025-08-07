class UserProfile {
  final int? id;
  final String name;
  final String voiceName;
  final String languageCode;
  final double speechRate;
  final double pitch;

  UserProfile({
    this.id,
    required this.name,
    required this.voiceName,
    required this.languageCode,
    required this.speechRate,
    required this.pitch,
  });

  UserProfile copyWith({
    int? id,
    String? name,
    String? voiceName,
    String? languageCode,
    double? speechRate,
    double? pitch,
  }) {
    return UserProfile(
      id: id ?? this.id,
      name: name ?? this.name,
      voiceName: voiceName ?? this.voiceName,
      languageCode: languageCode ?? this.languageCode,
      speechRate: speechRate ?? this.speechRate,
      pitch: pitch ?? this.pitch,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'voiceName': voiceName,
      'languageCode': languageCode,
      'speechRate': speechRate,
      'pitch': pitch,
    };
  }

  factory UserProfile.fromMap(Map<String, dynamic> map) {
    return UserProfile(
      id: map['id'],
      name: map['name'],
      voiceName: map['voiceName'],
      languageCode: map['languageCode'],
      speechRate: map['speechRate'],
      pitch: map['pitch'],
    );
  }

  @override
  String toString() {
    return 'UserProfile(id: $id, name: $name, voiceName: $voiceName, languageCode: $languageCode, speechRate: $speechRate, pitch: $pitch)';
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;

    return other is UserProfile &&
        other.id == id &&
        other.name == name &&
        other.voiceName == voiceName &&
        other.languageCode == languageCode &&
        other.speechRate == speechRate &&
        other.pitch == pitch;
  }

  @override
  int get hashCode {
    return id.hashCode ^
        name.hashCode ^
        voiceName.hashCode ^
        languageCode.hashCode ^
        speechRate.hashCode ^
        pitch.hashCode;
  }
}
