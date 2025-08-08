import 'package:wingmate/infrastructure/data/said_text_dao.dart';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';

import 'package:hive/hive.dart';
import 'package:http/http.dart' as http;
import 'package:wingmate/core/platform_directory.dart';
import 'package:uuid/uuid.dart';
import 'package:wingmate/domain/entities/voice.dart' as domain_models;
import 'package:wingmate/infrastructure/models/phrase_item.dart';
import 'package:wingmate/domain/services/speech_service.dart';
import 'package:wingmate/infrastructure/models/said_text_item.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';

/// A service class for handling Azure Text-to-Speech functionality.
/// This class manages the conversion of text to speech using Azure's TTS service,
/// handles audio playback, and manages the storage of generated audio files.
class AzureTts implements SpeechService {
  final String subscriptionKey;
  final String region;
  final Box settingsBox;
  final Box voiceBox;
  final BuildContext? context;
  final AudioPlayer player = AudioPlayer();
  final SaidTextDao saidTextDao;
  final Uuid _uuid = const Uuid();

  /// Creates a new instance of AzureTts.
  ///
  /// [subscriptionKey] - The Azure subscription key for authentication
  /// [region] - The Azure region endpoint
  /// [settingsBox] - Hive box for storing settings
  /// [voiceBox] - Hive box for storing voice settings
  /// [context] - BuildContext for showing error messages
  AzureTts({
    required this.subscriptionKey,
    required this.region,
    required this.settingsBox,
    required this.voiceBox,
    this.context,
    required this.saidTextDao,
  }) {
    // Initialize the player complete listener in the constructor
    player.onPlayerComplete.listen((event) {
      settingsBox.put('isPlaying', false);
    });
  }

  @override
  Future<List<domain_models.Voice>> getAvailableVoices() async {
    final url = Uri.parse('https://$region.tts.speech.microsoft.com/cognitiveservices/voices/list');
    final headers = {
      'Ocp-Apim-Subscription-Key': subscriptionKey,
    };

    try {
      final response = await http.get(url, headers: headers);
      if (response.statusCode == 200) {
        final List<Map<String, dynamic>> voicesJson = (json.decode(response.body) as List).cast<Map<String, dynamic>>();
        return voicesJson.map((json) => domain_models.Voice.fromMap(json)).toList();
      } else {
        throw Exception('Failed to load voices from Azure');
      }
    } catch (e) {
      _showError('Failed to fetch voices', e.toString());
      return [];
    }
  }

  /// Pauses the current audio playback.
  Future<void> stopSpeaking() async {
    try {
      await player.stop();
      settingsBox.put('isPlaying', false);
    } catch (e) {
      _showError('Failed to stop audio', e.toString());
    }
  }

  /// Speaks the given text, using global settings and caching.
  /// It first checks the cache, then generates and saves if not found.
  Future<void> speak(String text, {domain_models.Voice? voice, double? pitch, double? rate}) async {
    if (text.isEmpty) {
      _showError('Nothing to speak!', 'Please enter some text.');
      return;
    }

    try {
      final voice = voiceBox.get('currentVoice') as domain_models.Voice?;
      final voiceName = voice?.name ?? 'en-US-JennyNeural';
      final pitch = settingsBox.get('pitch', defaultValue: 1.0) as double;
      final rate = settingsBox.get('rate', defaultValue: 1.0) as double;

      // 1. Check cache first
      final cachedItem = await saidTextDao.getItemByTextAndVoice(text, voiceName, pitch, rate);
      if (cachedItem?.audioFilePath != null && File(cachedItem!.audioFilePath!).existsSync()) {
        debugPrint("Playing from cache: ${cachedItem.audioFilePath}");
        await play(DeviceFileSource(cachedItem.audioFilePath!));
        return;
      }

      // 2. If not in cache, generate, save, and play
      debugPrint("Cache miss. Generating new audio for: $text");
      await _generateAndPlay(text, voice, pitch, rate);
    } catch (e) {
      _handleError(e);
    }
  }

  /// Generates audio for a given text and voice configuration, then plays it without caching.
  /// Ideal for testing voice settings.
  Future<void> testVoice(String text, domain_models.Voice voice) async {
    if (text.isEmpty) return;
    try {
      final audioBytes = await _generateAudio(text, voice, voice.pitch ?? 1.0, voice.rate ?? 1.0);
      if (audioBytes != null) {
        // Play directly from memory without saving to a file
        await play(BytesSource(audioBytes));
      }
    } catch (e) {
      _handleError(e);
    }
  }

  /// Generates and caches audio for a SpeechItem, returning the file path.
  /// This is used for pre-caching audio for grid buttons.
  Future<String?> generateAndCacheAudioForItem(PhraseItem item) async {
    if (item.text == null || item.text!.isEmpty) {
      return null;
    }

    final voice = domain_models.Voice(
      name: item.voiceName ?? 'en-US-JennyNeural',
      primaryLanguage: item.selectedLanguage ?? 'en-US',
      pitch: item.pitch,
      rate: item.rateForSsml,
      supportedLanguages: [item.selectedLanguage ?? 'en-US'], // Assuming selected language is the only one relevant for this context
      pitchForSSML: item.pitchForSsml?.toString(), // Convert double? to String, provide default
      rateForSSML: item.rateForSsml?.toString(), // Convert double? to String, provide default
      selectedLanguage: item.selectedLanguage ?? 'en-US',
    );
    final pitch = item.pitch ?? 1.0;
    final rate = item.rateForSsml ?? 1.0;

    try {
      final audioBytes = await _generateAudio(item.text!, voice, pitch, rate);
      if (audioBytes != null) {
        final filePath = await _saveAudioToFile(audioBytes);
        // Also add to SaidText cache for general history
        final newItem = SaidTextItem(
          saidText: item.text,
          voiceName: voice.name,
          pitch: pitch,
          speed: rate,
          audioFilePath: filePath,
          date: DateTime.now().millisecondsSinceEpoch,
          createdAt: DateTime.now().millisecondsSinceEpoch,
        );
        await saidTextDao.insertHistorik(newItem);
        return filePath;
      }
    } catch (e) {
      _handleError(e);
    }
    return null;
  }

  /// Private helper to generate audio, save it to a file and the database, then play it.
  Future<void> _generateAndPlay(String text, domain_models.Voice? voice, double pitch, double rate) async {
    final audioBytes = await _generateAudio(text, voice, pitch, rate);
    if (audioBytes != null) {
      final filePath = await _saveAudioToFile(audioBytes);
      
      // Save metadata to DB for future cache hits
      final newItem = SaidTextItem(
        saidText: text,
        voiceName: voice?.name ?? 'en-US-JennyNeural',
        pitch: pitch,
        speed: rate,
        audioFilePath: filePath,
        date: DateTime.now().millisecondsSinceEpoch,
        createdAt: DateTime.now().millisecondsSinceEpoch,
      );
      await saidTextDao.insertHistorik(newItem);

      // Play the newly saved file
      await play(DeviceFileSource(filePath));
    }
  }
  
  /// Private helper to make the actual Azure TTS API request.
  Future<Uint8List?> _generateAudio(String text, domain_models.Voice? voice, double pitch, double rate) async {
    final ssml = _generateSSMLContent(text, voice!);
    debugPrint("SSML: $ssml");
    final url = Uri.parse('https://$region.tts.speech.microsoft.com/cognitiveservices/v1');
    final headers = {
      'Ocp-Apim-Subscription-Key': subscriptionKey,
      'Content-Type': 'application/ssml+xml',
      'X-Microsoft-OutputFormat': 'audio-16khz-128kbitrate-mono-mp3',
      'User-Agent': 'WingmateCrossPlatform',
    };

    final response = await http.post(url, headers: headers, body: ssml);

    if (response.statusCode == 200) {
      return response.bodyBytes;
    } else {
      debugPrint('Error from Azure TTS: ${response.statusCode} ${response.body}');
      throw Exception('Azure TTS request failed: ${response.statusCode} - ${response.body}');
    }
  }

  /// Private helper to save audio bytes to a unique file.
  Future<String> _saveAudioToFile(Uint8List audioBytes) async {
    final directory = await getPersistentStorageDirectory();
    final fileName = '${_uuid.v4()}.mp3'; // Use UUID for unique filenames
    final filePath = '${directory.path}/$fileName';
    final file = File(filePath);
    await file.writeAsBytes(audioBytes);
    return file.path;
  }

  String _generateSSMLContent(String text, domain_models.Voice voice) {
    final selectedVoice = voice.name;
    final primaryLanguage = voice.primaryLanguage;
    final pitchForSSML = voice.pitchForSSML ?? 'medium';
    final rateForSSML = voice.rateForSSML ?? 'medium';

    final rateTag = '<prosody rate="$rateForSSML">';
    final endRate = '</prosody>';
    final pitchTag = '<prosody pitch="$pitchForSSML">';
    final endPitch = '</prosody>';
    final langTag = '<lang xml:lang="$primaryLanguage">';
    final endLang = '</lang>';

    if (primaryLanguage?.isEmpty ?? true) {
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

  /// Plays audio from a given source.
  Future<void> play(Source source) async {
    try {
      // Stop any current playback
      await player.stop();
      // Play the new source
      await player.play(source);
      settingsBox.put('isPlaying', true);
    } catch (e) {
      _handleError(e);
    }
  }

  /// Handles errors that occur during TTS operations.
  void _handleError(dynamic error) {
    debugPrint('TTS Error: $error'); // Log the full error
    settingsBox.put('isPlaying', false); // Ensure isPlaying is reset on error
    if (error.toString().contains("Connection closed while receiving data")) {
      _showError('Message too long', 'Please try a shorter message');
    } else if (error.toString().contains("400")) {
      _showError('Invalid request', 'There was an issue with the speech parameters. Check voice, language, pitch, and rate settings.');
    } else {
      _showError('Connection error', 'Please check your internet connection or Azure settings.');
    }
  }

  /// Shows an error message to the user.
  void _showError(String title, String message) {
    if (context?.mounted == true) { // Check if the widget is still mounted before showing SnackBar
      ScaffoldMessenger.of(context!).showSnackBar(
        SnackBar(
          content: Text('$title: $message'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }
}