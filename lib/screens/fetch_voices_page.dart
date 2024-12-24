import 'package:flutter/material.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:hive/hive.dart';
import 'package:wingmancrossplatform/services/voice_settings.dart';
import 'package:wingmancrossplatform/utils/app_database.dart';

import '../models/voice_model.dart';
import '../services/voice_service.dart';
import '../utils/voice_dao.dart';
import '../utils/voice_item.dart';

class FetchVoicesPage extends StatefulWidget {
  final String endpoint;
  final String subscriptionKey;

  const FetchVoicesPage({
    Key? key,
    required this.endpoint,
    required this.subscriptionKey,
  }) : super(key: key);

  @override
  _FetchVoicesPageState createState() => _FetchVoicesPageState();
}

class _FetchVoicesPageState extends State<FetchVoicesPage> {
  late final VoiceService _voiceService;
  List<Map<String, dynamic>> _voices = [];
  List<Map<String, dynamic>> _filteredVoices = [];
  final _searchController = TextEditingController();
  bool _isLoading = true;
  bool _filterMale = false;
  bool _filterFemale = false;
  bool _filterNeutral = false;
  bool _filterMultilingual = false;

  @override
  void initState() {
    super.initState();
    _voiceService = VoiceService(
      endpoint: widget.endpoint,
      subscriptionKey: widget.subscriptionKey,
    );
    _loadVoices();
  }

  void _showVoiceSettingsDialog(
      String displayName, String shortName, List<String> supportedLanguages) {
    showDialog(
      context: context,
      builder: (context) {
        return VoiceSettingsDialog(
          shortName: shortName,
          displayName: displayName,
          supportedLanguages: supportedLanguages,
          onSave: (String language, double pitch, double rate) {
            _saveSelectedVoiceSettings(shortName, language, pitch, rate);
          },
        );
      },
    );
  }

  Future<void> _saveSelectedVoiceSettings(
      String shortName, String language, double pitch, double rate) async {
    final box = await Hive.box('');
    final config = {
      'voice': shortName,
      'language': language,
      'pitch': pitch,
      'rate': rate,
    };
    await box.put('voiceSettings', config);
  }

  Future<void> _loadVoices() async {
    setState(() => _isLoading = true);
    try {
      const expirationTime =
          60 * 24 * 60 * 24; // Set expiration time to 24 hours
      final database = AppDatabase();
      final voiceDao = VoiceDao(database);
      final existingVoices =
          await voiceDao.getAllVoices(expirationTime); // Get all voices

      if (existingVoices.isNotEmpty) {
        // Voices exist in the database, load them
        setState(() {
          _voices =
              existingVoices.map((voiceItem) => voiceItem.toMap()).toList();
          _filteredVoices = List.from(_voices); // Create a copy
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text("Voices loaded from database")),
        );
      } else {
        // No voices in the database, fetch from API
        await _fetchVoices();
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text("Error loading voices: $e")),
      );
    } finally {
      setState(() => _isLoading = false);
    }
  }

  Future<void> _fetchVoices() async {
    setState(() => _isLoading = true);

    try {
      final fetchedVoices = await _voiceService.fetchVoicesFromApi();
      print(fetchedVoices);

      setState(() {
        _voices = fetchedVoices;
        _filteredVoices = fetchedVoices;
      });

      // Save voices to the database
      await _saveVoicesToDatabase(fetchedVoices);
    } catch (e) {
      Fluttertoast.showToast(msg: "Error fetching voices: $e");
    } finally {
      setState(() => _isLoading = false);
    }
  }

  void _filterVoices(String query) {
    setState(() {
      _filteredVoices = _voices.where((voice) {
        final nameMatches =
            voice["name"].toLowerCase().contains(query.toLowerCase());

        final genderMatches =
            (!_filterMale && !_filterFemale && !_filterNeutral) ||
                (_filterMale && voice["gender"] == "Male") ||
                (_filterFemale && voice["gender"] == "Female") ||
                (_filterNeutral && voice["gender"] == "Neutral");

        final multilingualMatches = !_filterMultilingual ||
            (voice["supportedLanguages"] as String).isNotEmpty;

        return nameMatches && genderMatches && multilingualMatches;
      }).toList();
      _sortVoices();
    });
  }

  void _sortVoices() {
    _filteredVoices.sort((a, b) => a["locale"].compareTo(b["locale"]));
  }

  Future<void> _saveVoicesToDatabase(List<Map<String, dynamic>> voices) async {
    final database = AppDatabase(); // Get the database instance
    final voiceDao = VoiceDao(database);

    // Delete all previous entries if needed (optional)
    await voiceDao.deleteAll(); // Optional if you want to clear old data

    for (var voice in voices) {
      final voiceItem = VoiceItem(
        name: voice['name'],
        supportedLanguages: (voice['supportedLanguages'] as String),
        gender: voice['gender'],
        primaryLanguage: voice['locale'],
        createdAt: DateTime.now().millisecondsSinceEpoch,
        displayName: voice['displayName'],
      );

      // Insert voice into the database using the DAO
      await voiceDao.insert(voiceItem);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Available Voices'), // Set the title
      ),
      body: _isLoading // Loading state
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: TextField(
                    controller: _searchController,
                    onChanged: _filterVoices,
                    decoration: const InputDecoration(
                      hintText: 'Search for voice name',
                      prefixIcon: Icon(Icons.search),
                    ),
                  ),
                ),
                Row(
                  children: [
                    _buildCheckbox("Male", _filterMale, (newValue) {
                      setState(() {
                        _filterMale = newValue; // Non-nullable assignment
                        _filterVoices(_searchController.text);
                      });
                    }),
                    _buildCheckbox("Female", _filterFemale, (newValue) {
                      setState(() {
                        _filterFemale = newValue; // Non-nullable assignment
                        _filterVoices(_searchController.text);
                      });
                    }),
                    _buildCheckbox("Neutral", _filterNeutral, (newValue) {
                      setState(() {
                        _filterNeutral = newValue; // Non-nullable assignment
                        _filterVoices(_searchController.text);
                      });
                    }),
                    _buildCheckbox("Multilingual", _filterMultilingual,
                        (newValue) {
                      setState(() {
                        _filterMultilingual =
                            newValue; // Non-nullable assignment
                        _filterVoices(_searchController.text);
                      });
                    }),
                  ],
                ),
                Expanded(
                  child: _filteredVoices.isEmpty &&
                          _searchController.text.isNotEmpty
                      ? const Center(child: Text("No voices found"))
                      : ListView.builder(
                          itemCount: _filteredVoices.length,
                          itemBuilder: (context, index) {
                            final voice = _filteredVoices[index];
                            return Card(
                              child: ListTile(
                                title: Text(voice["displayName"]),
                                subtitle: Text(
                                    "${voice["gender"]} • ${voice["locale"]}"),
                                onTap: () {
                                  final shortName = voice["name"];
                                  final supportedLanguages =
                                      (voice["supportedLanguages"] as String)
                                          .split(",")
                                          .map((e) => e.trim())
                                          .toList();
                                  _showVoiceSettingsDialog(voice["displayName"],
                                      shortName, supportedLanguages);
                                  SnackBar snackBar = SnackBar(
                                    content: Text(
                                        "Selected voice: ${voice["displayName"]}"),
                                  );
                                },
                              ),
                            );
                          },
                        ),
                ),
              ],
            ),
    );
  }

  Widget _buildCheckbox(
    String label,
    bool value,
    void Function(bool newValue) onChanged,
  ) {
    return Row(
      children: [
        Checkbox(
          value: value,
          onChanged: (bool? newValue) {
            if (newValue != null) {
              onChanged(newValue); // Safely pass the non-nullable value
            }
          },
        ),
        Text(label),
      ],
    );
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }
}
