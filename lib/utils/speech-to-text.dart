import 'dart:io';
import 'package:general_lib/general_lib.dart';
import 'package:whisper_library_dart/whisper_library_dart.dart';

void main(List<String> args) async {
  print("start");

  /// make sure you have downloaded model
  final String whisperModelPath =
      "../data/ai/whisper-ggml/";
  final WhisperLibrary whisperLibrary = WhisperLibrary(
    libraryWhisperPath: "../whisper_library_flutter/linux/libwhisper.so",
  );
  await whisperLibrary.ensureInitialized();
  final isLoadedModel = whisperLibrary.loadWhisperModel(
    whisperModelPath: whisperModelPath,
  );
  if (isLoadedModel == false) {
    print("cant loaded");
    exit(1);
  }
  final File fileWav = File(
    "../../native_lib/lib/whisper.cpp/samples/jfk.wav",
  );
  await Future.delayed(Duration(seconds: 2));
  DateTime dateTime = DateTime.now();
  final result = await whisperLibrary.transcribeToJson(
    fileWav: fileWav,
    useCountProccecors: 1,
    useCountThread: (Platform.numberOfProcessors / 2).toInt(),
  );
  print("seconds: ${DateTime.now().difference(dateTime)}");
  result.printPretty();
  exit(0);
}
