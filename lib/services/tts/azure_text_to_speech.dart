import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_dao.dart';
import 'package:wingmate/utils/said_text_item.dart';
import 'package:hive/hive.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:wingmate/utils/speech_service_config.dart';

class AzureTts {
  final String subscriptionKey;
  final String region;
  final Box<dynamic> settingsBox;
  final TextEditingController messageController;
  final Box<dynamic> voiceBox;
  final player = AudioPlayer();
  final BuildContext context;
  AzureTts(
      {required this.subscriptionKey,
      required this.region,
      required this.settingsBox,
      required this.messageController,
      required this.voiceBox,
      required this.context});
  // Pauses the audio playback if it's active.
  Future<void> pause() async {
    player.pause();
  }

  // Converts given text to speech using the selected Voice and Azure TTS service.
  Future<void> generateSSML(String text) async {
    debugPrint('generateSSML called with text: $text');
    Voice voice = voiceBox.get('currentVoice');
    final selectedVoice = voice.name; // 'the selected voice name';
    final selectedLanguage = voice.selectedLanguage; // 'the selected language';
    final url = Uri.parse(
        'https://$region.tts.speech.microsoft.com/cognitiveservices/v1');

    final headers = {
      'Ocp-Apim-Subscription-Key': subscriptionKey,
      'Content-Type': 'application/ssml+xml',
      'X-Microsoft-OutputFormat': 'audio-16khz-128kbitrate-mono-mp3',
      'User-Agent': 'WingmateCrossPlatform',
    };

    final ssml =
        '<speak version="1.0" xml:lang="en-US"> <voice name="$selectedVoice"><lang xml:lang="$selectedLanguage">$text</lang > </voice> </speak>';

    // Attempt to post the SSML body to Azure TTS endpoint
    try {
      final response = await http.post(url, headers: headers, body: ssml);
            

      if (response.statusCode == 200) {
        settingsBox.put('isPlaying', true);
        debugPrint('Azure TTS request succeeded ');
        final directory = await getApplicationDocumentsDirectory();
        final file = File('${directory.path}/temp_audio.mp3');
        await file.writeAsBytes(response.bodyBytes);
        // Save the audio file to the device and database
        saveAudioFile(text, file.readAsBytesSync(), selectedVoice);
      } else {
        debugPrint('Error: ${response.statusCode}, ${response.body}');
      }
    } catch (e) {
      debugPrint('Exception: $e');
    }
  }

  // Persists the audio file and text record in the local database.
  Future<void> saveAudioFile(
    String text,
    List<int> audioData,
    String voice,
  ) async {
    debugPrint('saveAudioFile called with voice: $voice');
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
      await playText(DeviceFileSource(filePath));
    } catch (e) {
      print('Error saving audio file: $e');
    }

  }
  Future <void> playText(
    DeviceFileSource filePath
  ) async {
    
    await player.play(filePath);
    
    player.onPlayerComplete.listen((event) {
      // Send isPlaying back to the main page with isPlaying = false
          
      settingsBox.put('isPlaying', false);
      });

  }

  Future<void> generateSSMLForItem(SaidTextItem saidTextItem) async {
    debugPrint('generateSSMLForItem called with text: ${saidTextItem.saidText}');
    Voice voice = voiceBox.get('currentVoice');
    final selectedVoice = voice.name;
    final selectedLanguage = voice.selectedLanguage;
    final url = Uri.parse(
        'https://$region.tts.speech.microsoft.com/cognitiveservices/v1');

    final headers = {
      'Ocp-Apim-Subscription-Key': subscriptionKey,
      'Content-Type': 'application/ssml+xml',
      'X-Microsoft-OutputFormat': 'audio-16khz-128kbitrate-mono-mp3',
      'User-Agent': 'WingmateCrossPlatform',
    };

    final ssml =
        '<speak version="1.0" xml:lang="en-US"> <voice name="$selectedVoice"><lang xml:lang="$selectedLanguage">${saidTextItem.saidText}</lang > </voice> </speak>';

    try {
      final response = await http.post(url, headers: headers, body: ssml);

      if (response.statusCode == 200) {
        debugPrint('Azure TTS request succeeded');
        final directory = await getApplicationDocumentsDirectory();
        final file = File('${directory.path}/audio_${DateTime.now().millisecondsSinceEpoch}.mp3');
        await file.writeAsBytes(response.bodyBytes);

        saidTextItem.audioFilePath = file.path;
        final SaidTextDao saidTextDao = SaidTextDao(AppDatabase());
        saidTextDao.insertHistorik(saidTextItem);
        debugPrint('Audio file saved at: ${file.path}');
      } else {
        debugPrint('Error: ${response.statusCode}, ${response.body}');
      }
    } catch (e) {
      debugPrint('Exception: $e');
    }
  }
}