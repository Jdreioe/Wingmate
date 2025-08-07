import 'package:wingmate/domain/entities/voice.dart';
import 'package:wingmate/domain/models/voice_model.dart' as domain_models;
import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/infrastructure/services/voice_settings.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:flutter/foundation.dart' show kIsWeb;


import 'package:wingmate/infrastructure/services/voice_service.dart';
import 'package:wingmate/infrastructure/data/voice_dao.dart';
import 'package:wingmate/infrastructure/models/voice_item.dart';
import 'package:wingmate/presentation/pages/main_page.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

class FetchVoicesPage extends StatefulWidget {
  final String endpoint;
  final String subscriptionKey;
  final UiSettings uiSettings;
  final Future<void> Function(String endpoint, String key, UiSettings uiSettings) onSaveSettings;

  const FetchVoicesPage({
    Key? key,
    required this.endpoint,
    required this.subscriptionKey,
    required this.uiSettings,
    required this.onSaveSettings,
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
      endpoint: widget.endpoint.toLowerCase().trim(),
      subscriptionKey: widget.subscriptionKey,
    );
    _loadVoices();
  }

  // Opens a Material dialog to customize voice settings.
  void _showVoiceSettingsDialog(
      String displayName, String shortName, List<String> supportedLanguages) {
    showDialog(
      context: context,
      builder: (context) {
        return VoiceSettingsDialog(
          shortName: shortName,
          displayName: displayName,
          supportedLanguages: supportedLanguages,
          onSave: (String language, double pitch, double rate,
              String pitchForSSML, String rateForSSML) {
            _saveSelectedVoiceSettings(shortName, supportedLanguages, language,
                pitch, rate, pitchForSSML, rateForSSML);
          },
        );
      },
    );
  }

  // Saves the user's selected voice to Hive.
  Future<void> _saveSelectedVoiceSettings(
      String shortName,
      List<String> supportedLanguages,
      String language,
      double pitch,
      double rate,
      String pitchForSSML,
      String rateForSSML) async {
    final box = await Hive.box('selectedVoice');
    final Voice voice = Voice(
      name: shortName,
      supportedLanguages: supportedLanguages,
      primaryLanguage: language,
      pitch: pitch,
      rate: rate,
      pitchForSSML: pitchForSSML,
      rateForSSML: rateForSSML,
      selectedLanguage: language,
    );
    final voiceModel = domain_models.Voice.fromDomain(voice);
    await box.put('currentVoice', voiceModel);
    await widget.onSaveSettings(widget.endpoint, widget.subscriptionKey, widget.uiSettings);
  }

  // Loads voices from the database or fetches them from the API if needed.
  Future<void> _loadVoices() async {
    setState(() => _isLoading = true);
    try {
      const expirationTime = 60 * 24 * 60 * 24;
      final database = AppDatabase();
      final voiceDao = VoiceDao(database);
      final existingVoices = await voiceDao.getVoices();

      if (existingVoices.isNotEmpty) {
        setState(() {
          _voices = existingVoices.map((voice) => VoiceItem.fromDomain(voice).toMap()).toList();
          _filteredVoices = List.from(_voices);
        });
        _showToast("Voices loaded from database");
      } else {
        await _fetchVoices();
      }
    } catch (e) {
      _showToast("Error loading voices: $e");
    } finally {
      setState(() => _isLoading = false);
    }
  }

  Future<void> _fetchVoices() async {
    debugPrint('_fetchVoices invoked');
    setState(() => _isLoading = true);

    try {
      final fetchedVoices = await _voiceService.fetchVoicesFromApi();

      setState(() {
        _voices = fetchedVoices;
        _filteredVoices = fetchedVoices;
      });

      if (fetchedVoices.isNotEmpty) {
        debugPrint('Fetched voices count: ${fetchedVoices.length}');
      }

      await _saveVoicesToDatabase(fetchedVoices);
    } catch (e) {
      _showToast("Error fetching voices: $e");
    } finally {
      setState(() => _isLoading = false);
    }
  }

  // Filters voices based on user search and applied gender/language filters.
  void _filterVoices(String query) {
    debugPrint('_filterVoices called with query: $query');
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

  // Sorts voices by locale for consistency in the list display.
  void _sortVoices() {
    _filteredVoices.sort((a, b) => a["locale"].compareTo(b["locale"]));
  }

  // Persists the fetched voices into the local database for offline use.
  Future<void> _saveVoicesToDatabase(List<Map<String, dynamic>> voices) async {
    final database = AppDatabase();
    final voiceDao = VoiceDao(database);
    await voiceDao.deleteAll();

    for (var voice in voices) {
      final voiceItem = VoiceItem.fromMap(voice);
      await voiceDao.saveVoice(voiceItem.toDomain());
    }
  }

  @override
  Widget build(BuildContext context) {
    // Consolidated into a single Material Scaffold for all platforms
    return Scaffold(
      appBar: AppBar(
        title: const Text('Available Voices'),
      ),
      body: _buildContent(),
    );
  }

  Widget _buildContent() {
    return _isLoading
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
                      _filterMale = newValue;
                      _filterVoices(_searchController.text);
                    });
                  }),
                  _buildCheckbox("Female", _filterFemale, (newValue) {
                    setState(() {
                      _filterFemale = newValue;
                      _filterVoices(_searchController.text);
                    });
                  }),
                  _buildCheckbox("Neutral", _filterNeutral, (newValue) {
                    setState(() {
                      _filterNeutral = newValue;
                      _filterVoices(_searchController.text);
                    });
                  }),
                  _buildCheckbox("Multilingual", _filterMultilingual, (newValue) {
                    setState(() {
                      _filterMultilingual = newValue;
                      _filterVoices(_searchController.text);
                    });
                  }),
                ],
              ),
              Expanded(
                child: _filteredVoices.isEmpty && _searchController.text.isNotEmpty
                    ? const Center(child: Text("No voices found"))
                    : ListView.builder(
                        itemCount: _filteredVoices.length,
                        itemBuilder: (context, index) {
                          final voice = _filteredVoices[index];
                          return Card(
                            child: ListTile(
                              title: Text(voice["displayName"]),
                              subtitle: Text("${voice["gender"]} • ${voice["locale"]}"),
                              onTap: () {
                                final shortName = voice["name"];
                                final supportedLanguages =
                                    (voice["supportedLanguages"] as String)
                                        .split(",")
                                        .map((e) => e.trim())
                                        .toList();
                                _showVoiceSettingsDialog(
                                    voice["displayName"],
                                    shortName,
                                    supportedLanguages);
                              },
                            ),
                          );
                        },
                      ),
              ),
              Align(
                alignment: Alignment.bottomRight,
                child: ElevatedButton(
                  onPressed: () {
                    Navigator.pushReplacement(
                      context,
                      MaterialPageRoute(
                        builder: (context) => MainPage(
                          speechServiceEndpoint: widget.endpoint,
                          speechServiceKey: widget.subscriptionKey,
                          uiSettings: widget.uiSettings,
                          onSaveSettings: widget.onSaveSettings,
                        ),
                      ),
                    );
                  },
                  child: const Text('Next'),
                ),
              ),
            ],
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
              onChanged(newValue);
            }
          },
        ),
        Text(label),
      ],
    );
  }

  void _showToast(String message) {
    // Consolidated to use SnackBar for all platforms
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }
}