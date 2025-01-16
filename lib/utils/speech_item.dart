class SpeechItem {
  int? id;
  String? name;
  String? text;
  bool? isFolder;
int? parentId;
  int? createdAt;
  int? position;
  String? filePath;
  String? voiceName;
  double? pitch;
  String? selectedLanguage;
  double? rateForSsml;
  double? pitchForSsml;
  SpeechItem(
      {this.id,
      this.name,
      this.text,
      this.isFolder,
      this.parentId,
      this.createdAt,
      this.position,
      this.filePath,
      this.voiceName,
      this.pitch,
      this.selectedLanguage,
      this.rateForSsml,
      this.pitchForSsml
      });
  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'text': text,
      'isFolder': isFolder == true ? 1 : 0,
      'parentId': parentId,
      'createdAt': createdAt,
      'position': position,
      'filePath': filePath,
      'voiceName': voiceName,
      'pitch': pitch,
      'selectedLanguage': selectedLanguage,
      'rateForSsml': rateForSsml,
      'pitchForSsml': pitchForSsml
    };
  }

  static SpeechItem fromMap(Map<String, dynamic> map) {
    return SpeechItem(
      id: map['id'],
      name: map['name'],
      text: map['text'],
      isFolder: map['isFolder'] == 1,
      parentId: map['parentId'],
      createdAt: map['createdAt'],
      position: map['position'],
      filePath: map['filePath'],
      voiceName: map['voiceName'],
      pitch: map['pitch'],
      selectedLanguage: map['selectedLanguage'],
      rateForSsml: map['rateForSsml'],
      pitchForSsml: map['pitchForSsml']
    );
  }
}
