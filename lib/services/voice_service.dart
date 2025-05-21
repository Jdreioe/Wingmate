import 'dart:convert';
import 'dart:developer';
import 'package:http/http.dart' as http;
import 'profile_service.dart'; // Added import

class VoiceService {
  final String endpoint;
  final String subscriptionKey;
  final ProfileService _profileService; // Added ProfileService

  VoiceService({
    required this.endpoint,
    required this.subscriptionKey,
    required ProfileService profileService, // Added to constructor
  }) : _profileService = profileService;

  Future<List<Map<String, dynamic>>> fetchVoicesFromApi() async {
    final url = Uri.parse(
        "https://${endpoint}.tts.speech.microsoft.com/cognitiveservices/voices/list");
    try {
      final response = await http.get(
        url,
        headers: {
          "Ocp-Apim-Subscription-Key": subscriptionKey,
          "Content-Type": "ssml+xml",
          "X-Microsoft-OutputFormat": "riff-24khz-16bit-mono-pcm",
          "User-Agent": "Wingman 1.0",
        },
      );

      if (response.statusCode == 200) {
        final List<dynamic> voicesArray = json.decode(response.body);
        return voicesArray.map((voice) {
          return {
            "displayName": voice["DisplayName"],
            "name": voice["ShortName"],
            "gender": voice["Gender"],
            "locale": voice["Locale"],
            "supportedLanguages":
                voice["SecondaryLocaleList"]?.join(", ") ?? "",
          };
        }).toList();
      } else {
        throw Exception(
            "Failed to fetch voices. Status code: ${response.statusCode}");
      }
    } catch (e) {
      log("Error fetching voices: $e");
      return [];
    }
  }
}
