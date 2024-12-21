import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:just_audio/just_audio.dart';

class AzureTts {
  final String subscriptionKey;
  final String region;

  AzureTts({required this.subscriptionKey, required this.region});

  Future<void> speakText(String text) async {
    final url = Uri.parse(
        'https://$region.tts.speech.microsoft.com/cognitiveservices/v1');

    final headers = {
      'Ocp-Apim-Subscription-Key': subscriptionKey,
      'Content-Type': 'application/ssml+xml',
      'X-Microsoft-OutputFormat':
          'audio-16khz-128kbitrate-mono-mp3', // Or other format
      'User-Agent': 'Flutter Azure TTS Example', // Important for identification
    };

    final ssml =
        '<speak version="1.0" xml:lang="en-US"><voice name="en-US-JennyNeural">$text</voice></speak>';

    try {
      final response = await http.post(url, headers: headers, body: ssml);

      if (response.statusCode == 200) {
        final player = AudioPlayer();
        await player.setAudioBytes(response.bodyBytes);
        player.play();
      } else {
        debugPrint('Error: ${response.statusCode}, ${response.body}');
      }
    } catch (e) {
      debugPrint('Exception: $e');
    }
  }
}

// Example usage (similar to previous example):
// ... (rest of the widget code)