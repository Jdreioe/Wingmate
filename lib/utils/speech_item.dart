class SpeechItem {
  int? id;
  String? name;
  String? text;
  bool? isFolder;
  int? parentId;
  int? createdAt;
  int? position;
  SpeechItem(
      {this.id,
      this.name,
      this.text,
      this.isFolder,
      this.parentId,
      this.createdAt,
      this.position
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
    );
  }
}
