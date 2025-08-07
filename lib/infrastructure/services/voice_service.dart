import 'dart:convert';
import 'dart:developer';
import 'package:http/http.dart' as http;
// Removed: import 'profile_service.dart'; // Not needed if ProfileService is not used here

class VoiceService {
  final String endpoint; // e.g., "eastus"
  final String subscriptionKey;

  VoiceService({
    required this.endpoint,
    required this.subscriptionKey,
    // Removed: required ProfileService profileService,
  }); // Removed: : _profileService = profileService;

  Future<List<Map<String, dynamic>>> fetchVoicesFromApi() async {
    // The endpoint should be just the region (e.g., "eastus") for this URL format.
    final url = Uri.parse(
        "https://$endpoint.tts.speech.microsoft.com/cognitiveservices/voices/list");
    try {
      final response = await http.get(
        url,
        headers: {
          "Ocp-Apim-Subscription-Key": subscriptionKey,
          // The following headers are typically for synthesizing speech, not listing voices.
          // They are not required and can be removed for the /voices/list endpoint.
          // "Content-Type": "ssml+xml",
          // "X-Microsoft-OutputFormat": "riff-24khz-16bit-mono-pcm",
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
            // Safely access SecondaryLocaleList, ensuring it's a List before joining
            "supportedLanguages":
                (voice["SecondaryLocaleList"] is List)
                    ? (voice["SecondaryLocaleList"] as List).cast<String>().join(", ")
                    : "",
          };
        }).toList();
      } else {
        throw Exception(
            "Failed to fetch voices. Status code: ${response.statusCode}. Body: ${response.body}");
      }
    } catch (e) {
      log("Error fetching voices: $e");
      return [];
    }
  }
}