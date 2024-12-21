class SaidTextItem {
  int? id;
  int? date; // Store as integer (milliseconds since epoch)
  String? saidText;
  String? voiceName;
  double? pitch;
  double? speed;
  String? audioFilePath;
  int? createdAt;

  SaidTextItem(
      {this.id,
      this.date,
      this.saidText,
      this.voiceName,
      this.pitch,
      this.speed,
      this.audioFilePath,
      this.createdAt});

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'date': date,
      'saidText': saidText,
      'voiceName': voiceName,
      'pitch': pitch,
      'speed': speed,
      'audioFilePath': audioFilePath,
      'createdAt': createdAt,
    };
  }

  static SaidTextItem fromMap(Map<String, dynamic> map) {
    return SaidTextItem(
      id: map['id'],
      date: map['date'],
      saidText: map['saidText'],
      voiceName: map['voiceName'],
      pitch: map['pitch']?.toDouble(), // Ensure correct type conversion
      speed: map['speed']?.toDouble(), // Ensure correct type conversion
      audioFilePath: map['audioFilePath'],
      createdAt: map['createdAt'],
    );
  }
}
