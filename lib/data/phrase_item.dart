class PhraseItem {
  int? id;
  String? name;
  String? text;
  bool? isCategory;
int? parentId;
  int? createdAt;
  int? position;
  String? filePath;
  String? voiceName;
  double? pitch;
  String? selectedLanguage;
  double? rateForSsml;
  double? pitchForSsml;
  String? imagePath; // New field for image path
  String? backgroundColor;
  String? labelColor;
  PhraseItem(
      {this.id,
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
      this.backgroundColor,
      this.labelColor,
      this.imagePath, // Include in constructor
      });
  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'text': text,
      'isCategory': isCategory == true ? 1 : 0,
      'parentId': parentId,
      'createdAt': createdAt,
      'position': position,
      'filePath': filePath,
      'voiceName': voiceName,
      'pitch': pitch,
      'selectedLanguage': selectedLanguage,
      'rateForSsml': rateForSsml,
      'pitchForSsml': pitchForSsml,
      'backgroundColor': backgroundColor,
      'labelColor': labelColor,
      'imagePath': imagePath, // Include in toMap
    };
  }

  static PhraseItem fromMap(Map<String, dynamic> map) {
    return PhraseItem(
      id: map['id'],
      name: map['name'],
      text: map['text'],
      isCategory: map['isCategory'] == 1,
      parentId: map['parentId'],
      createdAt: map['createdAt'],
      position: map['position'],
      filePath: map['filePath'],
      voiceName: map['voiceName'],
      pitch: map['pitch'],
      selectedLanguage: map['selectedLanguage'],
      rateForSsml: map['rateForSsml'],
      pitchForSsml: map['pitchForSsml'],
      backgroundColor: map['backgroundColor'],
      labelColor: map['labelColor'],
      imagePath: map['imagePath'], // Include in fromMap
    );
  }
}
