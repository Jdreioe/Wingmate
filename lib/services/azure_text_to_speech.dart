import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:wingmancrossplatform/models/voice_model.dart';
import 'package:wingmancrossplatform/utils/app_database.dart';
import 'package:wingmancrossplatform/utils/said_text_dao.dart';
import 'package:wingmancrossplatform/utils/said_text_item.dart';
import 'package:hive/hive.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:wingmancrossplatform/utils/speech_service_config.dart';

class AzureTts {
  final String subscriptionKey;
  final String region;
  final Box<dynamic> settingsBox;
  final TextEditingController messageController;
  final Box<dynamic> voiceBox;
  AzureTts(
      {required this.subscriptionKey,
      required this.region,
      required this.settingsBox,
      required this.messageController,
      required this.voiceBox});

  Future<void> speakText(String text) async {
    Voice voice = voiceBox.get('currentVoice');
    SpeechServiceConfig settings = settingsBox.get('voiceSettings');
    debugPrint("Den valgte stemme er: " +
        voice.name +
        " og sproget den skal tale er: " +
        voice.selectedLanguage);
    final selectedVoice = voice.name; // 'the selected voice name';
    final url = Uri.parse(
        'https://$region.tts.speech.microsoft.com/cognitiveservices/v1');

    final headers = {
      'Ocp-Apim-Subscription-Key': subscriptionKey,
      'Content-Type': 'application/ssml+xml',
      'X-Microsoft-OutputFormat': 'audio-16khz-128kbitrate-mono-mp3',
      'User-Agent': 'WingmateCrossPlatform',
    };

    final ssml =
        '<speak version="1.0" xml:lang="en-US"><voice name="$selectedVoice">$text</voice></speak>';

    debugPrint('URL: $url');
    debugPrint('Headers: $headers');
    debugPrint('SSML: $ssml');

    try {
      final response = await http.post(url, headers: headers, body: ssml);

      if (response.statusCode == 200) {
        final player = AudioPlayer();
        final directory = await getApplicationDocumentsDirectory();
        final file = File('${directory.path}/temp_audio.mp3');
        await file.writeAsBytes(response.bodyBytes);
        await player.play(DeviceFileSource(file.path));
        saveAudioFile(text, file.readAsBytesSync(), selectedVoice);
      } else {
        debugPrint('Error: ${response.statusCode}, ${response.body}');
      }
    } catch (e) {
      debugPrint('Exception: $e');
    }
  }

  Future<void> saveAudioFile(
    String text,
    List<int> audioData,
    String voice,
  ) async {
    final SaidTextDao saidTextDao = SaidTextDao(AppDatabase());
    try {
      final Directory directory = await getApplicationDocumentsDirectory();
      final String filePath =
          '${directory.path}/audio_${DateTime.now().millisecondsSinceEpoch}.mp3';
      final File file = File(filePath);
      await file.writeAsBytes(audioData);

      SaidTextItem saidTextItem = SaidTextItem(
          saidText: text,
          audioFilePath: filePath,
          date: DateTime.now().millisecondsSinceEpoch,
          voiceName: voice);
      saidTextDao.insertHistorik(saidTextItem);
      debugPrint('Audio file saved at: $filePath');
    } catch (e) {
      print('Error saving audio file: $e');
    }
  }
}
