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

  // Functions to get active profile settings
  final Future<String?> Function()? getActiveVoiceName;
  final Future<String?> Function()? getActiveLanguageCode;
  final Future<double?> Function()? getActiveSpeechRate;
  final Future<double?> Function()? getActivePitch;

  /// Creates a new instance of AzureTts.
  ///
  /// [subscriptionKey] - The Azure subscription key for authentication
  /// [region] - The Azure region endpoint
  /// [settingsBox] - Hive box for storing settings
  /// [messageController] - Controller for managing text input
  /// [voiceBox] - Hive box for storing voice settings
  /// [context] - BuildContext for showing error messages
  /// [getActiveVoiceName] - Function to get the active profile's voice name
  /// [getActiveLanguageCode] - Function to get the active profile's language code
  /// [getActiveSpeechRate] - Function to get the active profile's speech rate
  /// [getActivePitch] - Function to get the active profile's pitch
  AzureTts({
    required this.subscriptionKey,
    required this.region,
    required this.settingsBox,
    required this.messageController,
    required this.voiceBox,
    required this.context,
    this.getActiveVoiceName,
    this.getActiveLanguageCode,
    this.getActiveSpeechRate,
    this.getActivePitch,
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
  /// [useProfileSettings] - Whether to use settings from ProfileService (defaults to true)
  Future<void> generateSSML(String text, {bool useProfileSettings = true}) async {
    try {
      final voice = await _getCurrentVoice(useProfileSettings: useProfileSettings);
      final ssml = await _generateSSMLContent(text, voice, useProfileSettings: useProfileSettings);
      await _processSSML(ssml, voice, text);
    } catch (e) {
      _handleError(e);
    }
  }

  /// Generates SSML for a specific speech item.
  ///
  /// [speechItem] - The speech item to convert to speech
  /// [useProfileSettings] - Whether to use settings from ProfileService (defaults to true)
  Future<void> generateSSMLForItem(SpeechItem speechItem, {bool useProfileSettings = true}) async {
    try {
      // Pass useProfileSettings to _getCurrentVoice and _generateSSMLContent
      final voice = await _getCurrentVoice(useProfileSettings: useProfileSettings);
      final ssml = await _generateSSMLContent(speechItem.text ?? '', voice, useProfileSettings: useProfileSettings);
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

  // Updated _getCurrentVoice to be async and use profile settings if available
  Future<Voice> _getCurrentVoice({bool useProfileSettings = true}) async {
    if (useProfileSettings &&
        getActiveVoiceName != null &&
        getActiveLanguageCode != null &&
        getActiveSpeechRate != null &&
        getActivePitch != null) {
      final voiceName = await getActiveVoiceName!();
      final languageCode = await getActiveLanguageCode!();
      final speechRate = await getActiveSpeechRate!(); // Profile speechRate (e.g., 0.0 to 2.0)
      final pitch = await getActivePitch!(); // Profile pitch (e.g., 0.0 to 2.0)

      if (voiceName != null && languageCode != null) {
        // Construct a Voice object from profile settings.
        // Other fields like 'gender', 'displayName' are not directly in UserProfile.
        // They might come from a full voice list if needed, or use defaults.
        // pitchForSSML and rateForSSML will be derived from 'pitch' and 'speechRate'
        // in _generateSSMLContent based on Azure's specific requirements.
        return Voice(
          name: voiceName,
          selectedLanguage: languageCode, // Used for xml:lang
          locale: languageCode, // Primary locale for the voice
          pitch: pitch ?? 1.0, // Store the original profile pitch (0.0-2.0)
          rate: speechRate ?? 1.0, // Store the original profile rate (0.0-2.0)
          // The following are placeholders; actual SSML values are set in _generateSSMLContent
          pitchForSSML: pitch ?? 1.0, 
          rateForSSML: speechRate ?? 1.0,
          displayName: voiceName, // Or a more descriptive name if available
          gender: "", // Gender info isn't in UserProfile
        );
      }
    }

    // Fallback to Hive voiceBox (legacy behavior or when not using profile settings)
    final voice = voiceBox.get('currentVoice') as Voice?;
    if (voice == null) {
      // Fallback to a very basic default if nothing is found from Hive either
      // This ensures the app can still function minimally.
      return Voice(
        name: "en-US-AriaNeural", // A common default voice
        selectedLanguage: "en-US",
        locale: "en-US",
        pitch: 1.0,
        rate: 1.0,
        pitchForSSML: 1.0, // Default SSML pitch value
        rateForSSML: 1.0, // Default SSML rate value
        displayName: "Default Fallback Voice",
        gender: "Female", // Common gender for AriaNeural
      );
    }
    return voice;
  }

  /// Generates SSML content for the given text and voice.
  /// If useProfileSettings is true, it will try to use rate/pitch from ProfileService.
  Future<String> _generateSSMLContent(String text, Voice voice, {bool useProfileSettings = true}) async {
    String ssmlVoiceName = voice.name;
    String ssmlLang = voice.selectedLanguage ?? "en-US"; // Fallback language for <speak> tag

    // Determine effective rate and pitch
    double currentRate = voice.rate; // This is the profile rate (e.g., 0.0-2.0) or Voice.rate
    double currentPitch = voice.pitch; // This is the profile pitch (e.g., 0.0-2.0) or Voice.pitch

    if (useProfileSettings) {
      // Override with fresh profile values if available and useProfileSettings is true
      if (getActiveSpeechRate != null) {
        currentRate = await getActiveSpeechRate!() ?? currentRate;
      }
      if (getActivePitch != null) {
        currentPitch = await getActivePitch!() ?? currentPitch;
      }
      // If getActiveVoiceName and getActiveLanguageCode were also to be dynamically fetched here:
      // if (getActiveVoiceName != null) ssmlVoiceName = await getActiveVoiceName!() ?? ssmlVoiceName;
      // if (getActiveLanguageCode != null) ssmlLang = await getActiveLanguageCode!() ?? ssmlLang;
      // However, _getCurrentVoice already sets these on the 'voice' object for the current operation.
    }

    // Convert profile rate (e.g., 1.0 = normal, 0.5 = half, 2.0 = double) to SSML rate string.
    // Azure TTS prosody rate is a multiplier.
    String ssmlRateValue = currentRate.toStringAsFixed(2);

    // Convert profile pitch (e.g., 1.0 = normal) to SSML pitch string.
    // Azure TTS prosody pitch can be relative (e.g., "+20.00%", "-1st") or absolute.
    // Let's use relative percentage: (pitch - 1.0) results in a multiplier.
    // 0.0 -> -100% (interpreted as 0x speed, bad) or "x-low"
    // 1.0 -> 0% (normal)
    // 2.0 -> +100% (double pitch, also likely too high, typically up to +50% is used)
    // A common mapping: Profile Pitch 1.0 = SSML Pitch 0%. Profile Pitch 1.5 = SSML Pitch +25%. Profile Pitch 0.5 = SSML Pitch -25%.
    // So, (currentPitch - 1.0) / 2.0 * 100 to get a +/- 50% range from 0.0-2.0 profile range.
    // (currentPitch - 1.0) * 50 gives a range of -50% to +50% for profile values 0.0 to 2.0
    String ssmlPitchValue = "${((currentPitch - 1.0) * 50).toStringAsFixed(0)}%";

    final rateProsodyTag = (currentRate != 1.0) ? '<prosody rate="$ssmlRateValue">' : '';
    final endRateProsodyTag = (currentRate != 1.0) ? '</prosody>' : '';
    final pitchProsodyTag = (currentPitch != 1.0) ? '<prosody pitch="$ssmlPitchValue">' : '';
    final endPitchProsodyTag = (currentPitch != 1.0) ? '</prosody>' : '';
    
    // Ensure the primary language tag for <speak> is sensible.
    final speakLang = ssmlLang.isNotEmpty ? ssmlLang : "en-US";

    // Note: The original code had a <lang> tag inside <voice> if selectedLanguage was not empty.
    // This is generally okay. Azure uses the voice's inherent language, but <lang> can override for a segment.
    // For simplicity here, we're setting xml:lang on the <speak> tag and relying on the voice's natural language.
    // If fine-grained control per-segment is needed, the <lang> tag logic could be re-introduced.

    return '''
      <speak version="1.0" xml:lang="$speakLang" xmlns="http://www.w3.org/2001/10/synthesis">
        <voice name="$ssmlVoiceName">
          $pitchProsodyTag
            $rateProsodyTag
              $text
            $endRateProsodyTag
          $pitchProsodyTag 
        </voice>
      </speak>
    ''';
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