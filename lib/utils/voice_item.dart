class VoiceItem {
  int? id;
  String? name;
  String? supportedLanguages;
  String? gender;
  String? primaryLanguage;
  String? locale;
  int? createdAt;
  String? displayName;
  String? rateForSSML;
  String? pitchForSSML;

  VoiceItem(
      {this.id,
      this.name,
      this.supportedLanguages,
      this.gender,
      this.primaryLanguage,
      this.createdAt,
      this.displayName,
      this.rateForSSML,
      this.pitchForSSML});


  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'supportedLanguages': supportedLanguages,
      'gender': gender,
      'locale': primaryLanguage,
      'createdAt': createdAt,
      'displayName': displayName,
      'rateForSSML': rateForSSML,
      'pitchForSSML': pitchForSSML,
    };
  }

  static VoiceItem fromMap(Map<String, dynamic> map) {
    return VoiceItem(
      id: map['id'],
      name: map['name'],
      supportedLanguages: map['supportedLanguages'],
      gender: map['gender'],
      primaryLanguage: map['locale'],
      createdAt: map['createdAt'],
      displayName: map['displayName'],
      rateForSSML: map['rateForSSML'],
      pitchForSSML: map['pitchForSSML'],
    );
  }
}
