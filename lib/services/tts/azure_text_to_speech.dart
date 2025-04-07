import 'dart:io';


import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_dao.dart';
import 'package:wingmate/utils/said_text_item.dart';
import 'package:hive/hive.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:wingmate/utils/speech_item.dart';
import 'package:wingmate/utils/speech_item_dao.dart';

/// A service class for handling Azure Text-to-Speech functionality.
/// This class manages the conversion of text to speech using Azure's TTS service,
/// handles audio playback, and manages the storage of generated audio files.
class AzureTts {
  final String subscriptionKey;
  final String region;
  final Box<dynamic> settingsBox;
  final TextEditingController messageController;
  final Box<dynamic> voiceBox;
  final player = AudioPlayer();
  final BuildContext context;

  /// Creates a new instance of AzureTts.
  /// 
  /// [subscriptionKey] - The Azure subscription key for authentication
  /// [region] - The Azure region endpoint
  /// [settingsBox] - Hive box for storing settings
  /// [messageController] - Controller for managing text input
  /// [voiceBox] - Hive box for storing voice settings
  /// [context] - BuildContext for showing error messages
  AzureTts({
    required this.subscriptionKey,
    required this.region,
    required this.settingsBox,
    required this.messageController,
    required this.voiceBox,
    required this.context,
  });

  /// Pauses the current audio playback.
  Future<void> pause() async {
    try {
      await player.pause();
      settingsBox.put('isPlaying', false);
    } catch (e) {
      _showError('Failed to pause audio', e.toString());
    }
  }

  /// Generates SSML and converts it to speech using Azure TTS service.
  /// 
  /// [text] - The text to convert to speech
  Future<void> generateSSML(String text) async {
    try {
      final voice = _getCurrentVoice();
      final ssml = _generateSSMLContent(text, voice);
      await _processSSML(ssml, voice, text);
    } catch (e) {
      _handleError(e);
    }
  }

  /// Generates SSML for a specific speech item.
  /// 
  /// [speechItem] - The speech item to convert to speech
  Future<void> generateSSMLForItem(SpeechItem speechItem) async {
    try {
      final voice = _getCurrentVoice();
      final ssml = _generateSSMLContent(speechItem.text ?? '', voice);
      await _processSSMLForItem(ssml, speechItem);
    } catch (e) {
      _handleError(e);
    }
  }

  /// Plays audio from a file source.
  /// 
  /// [source] - The audio file source to play
  Future<void> playText(DeviceFileSource source) async {
    try {
      await player.play(source);
      settingsBox.put('isPlaying', true);
      
      player.onPlayerComplete.listen((event) {
        settingsBox.put('isPlaying', false);
      });
    } catch (e) {
      _showError('Failed to play audio', e.toString());
    }
  }

  /// Gets the currently selected voice from storage.
  Voice _getCurrentVoice() {
    final voice = voiceBox.get('currentVoice') as Voice?;
    if (voice == null) {
      throw Exception('No voice selected');
    }
    return voice;
  }

  /// Generates SSML content for the given text and voice.
  String _generateSSMLContent(String text, Voice voice) {
    final selectedVoice = voice.name;
    final selectedLanguage = voice.selectedLanguage;
    final pitchForSSML = voice.pitchForSSML;
    final rateForSSML = voice.rateForSSML;

    final rateTag = '<prosody rate="$rateForSSML">';
    final endRate = '</prosody>';
    final pitchTag = '<prosody pitch="$pitchForSSML">';
    final endPitch = '</prosody>';
    final langTag = '<lang xml:lang="$selectedLanguage">';
    final endLang = '</lang>';

    if (selectedLanguage.isEmpty) {
      return '''
        <speak version="1.0" xml:lang="en-US">
          <voice name="$selectedVoice">
            $pitchTag
            $rateTag
            $text
            $endPitch
            $endRate
          </voice>
        </speak>
      ''';
    } else {
      return '''
        <speak version="1.0" xml:lang="en-US">
          <voice name="$selectedVoice">
            $langTag
            $text
            $endLang
          </voice>
        </speak>
      ''';
    }
  }

  /// Processes SSML content and saves the resulting audio.
  Future<void> _processSSML(String ssml, Voice voice, String text) async {
    final response = await _makeAzureRequest(ssml);
    
    if (response.statusCode == 200) {
      settingsBox.put('isPlaying', true);
      final file = await _saveAudioFile(response.bodyBytes);
      await _saveAudioRecord(text, file.path, voice);
      await playText(DeviceFileSource(file.path));
    } else {
      throw Exception('Azure TTS request failed: ${response.statusCode}');
    }
  }

  /// Processes SSML content for a speech item.
  Future<void> _processSSMLForItem(String ssml, SpeechItem speechItem) async {
    final response = await _makeAzureRequest(ssml);
    
    if (response.statusCode == 200) {
      final directory = await getApplicationDocumentsDirectory();
      final file = File('${directory.path}/audio_${DateTime.now().millisecondsSinceEpoch}.mp3');
      await file.writeAsBytes(response.bodyBytes);

      speechItem.filePath = file.path;
      final speechItemDao = SpeechItemDao(AppDatabase());
      await speechItemDao.updateItem(speechItem);
    } else {
      throw Exception('Azure TTS request failed: ${response.statusCode}');
    }
  }

  /// Makes a request to the Azure TTS service.
  Future<http.Response> _makeAzureRequest(String ssml) async {
    final url = Uri.parse('https://$region.tts.speech.microsoft.com/cognitiveservices/v1');
    final headers = {
      'Ocp-Apim-Subscription-Key': subscriptionKey,
      'Content-Type': 'application/ssml+xml',
      'X-Microsoft-OutputFormat': 'audio-16khz-128kbitrate-mono-mp3',
      'User-Agent': 'WingmateCrossPlatform',
    };

    return await http.post(url, headers: headers, body: ssml);
  }

  /// Saves an audio file to the device.
  Future<File> _saveAudioFile(List<int> audioData) async {
    final directory = await getApplicationDocumentsDirectory();
    final file = File('${directory.path}/temp_audio.mp3');
    await file.writeAsBytes(audioData);
    return file;
  }

  /// Saves an audio record to the database.
  Future<void> _saveAudioRecord(
    String text,
    String filePath,
    Voice voice,
  ) async {
    final saidTextDao = SaidTextDao(AppDatabase());
    final saidTextItem = SaidTextItem(
      saidText: text,
      audioFilePath: filePath,
      date: DateTime.now().millisecondsSinceEpoch,
      voiceName: voice.name,
      pitch: voice.pitch,
      speed: voice.rate,
      position: 0,
      primaryLanguage: voice.selectedLanguage,
      createdAt: DateTime.now().millisecondsSinceEpoch,
    );

    await saidTextDao.insertHistorik(saidTextItem);
  }

  /// Handles errors that occur during TTS operations.
  void _handleError(dynamic error) {
    if (error.toString().contains("Connection closed while receiving data")) {
      _showError('Message too long', 'Please try a shorter message');
    } else {
      _showError('Connection error', 'Please check your internet connection');
    }
  }

  /// Shows an error message to the user.
  void _showError(String title, String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('$title: $message'),
        backgroundColor: Colors.red,
      ),
    );
  }
}