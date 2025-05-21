import 'package:flutter/material.dart';
import '../models/user_profile.dart';
import '../models/voice_model.dart'; // Assuming VoiceModel exists and is relevant
import '../services/profile_service.dart';
import '../services/voice_service.dart'; // For fetching available voices

class EditProfilePage extends StatefulWidget {
  final UserProfile? existingProfile;
  final ProfileService profileService;
  final VoiceService voiceService; // To fetch voices

  const EditProfilePage({
    Key? key,
    this.existingProfile,
    required this.profileService,
    required this.voiceService,
  }) : super(key: key);

  @override
  _EditProfilePageState createState() => _EditProfilePageState();
}

class _EditProfilePageState extends State<EditProfilePage> {
  final _formKey = GlobalKey<FormState>();

  late TextEditingController _nameController;

  String? _selectedVoiceName;
  String? _selectedLanguageCode;
  double _speechRate = 1.0;
  double _pitch = 1.0;

  List<Map<String, dynamic>> _availableVoices = []; // Store maps directly from VoiceService
  List<String> _languagesForSelectedVoice = [];
  bool _isLoadingVoices = true;
  bool _isSaving = false;

  bool get _isEditMode => widget.existingProfile != null;

  @override
  void initState() {
    super.initState();

    _nameController = TextEditingController();

    if (_isEditMode) {
      final profile = widget.existingProfile!;
      _nameController.text = profile.name;
      _selectedVoiceName = profile.voiceName;
      _selectedLanguageCode = profile.languageCode;
      _speechRate = profile.speechRate;
      _pitch = profile.pitch;
    } else {
      // Sensible defaults for create mode (can be adjusted)
      _speechRate = 1.0;
      _pitch = 1.0;
    }

    _loadAvailableVoices();
  }

  Future<void> _loadAvailableVoices() async {
    setState(() {
      _isLoadingVoices = true;
    });
    try {
      _availableVoices = await widget.voiceService.fetchVoicesFromApi();

      if (_selectedVoiceName != null && _availableVoices.any((v) => v['name'] == _selectedVoiceName)) {
        // If editing and the selected voice is valid, populate languages
        _updateLanguagesForVoice(_selectedVoiceName!);
      }

    } catch (e) {
      print("Error loading voices: $e");
      // Show error (e.g., SnackBar)
    } finally {
      setState(() {
        _isLoadingVoices = false;
      });
    }
  }
  
  // Helper to update the language dropdown when a voice is selected
  void _updateLanguagesForVoice(String voiceShortName) {
    final selectedVoiceMap = _availableVoices.firstWhere(
      (v) => v['name'] == voiceShortName, // 'name' is the short name from API
      orElse: () => <String, dynamic>{}, // Return an empty map if not found
    );

    if (selectedVoiceMap.isEmpty) {
      _languagesForSelectedVoice = [];
      _selectedLanguageCode = null;
      return;
    }

    // 'locale' is the primary language
    String primaryLocale = selectedVoiceMap['locale'] as String? ?? '';
    // 'supportedLanguages' is a comma-separated string from 'SecondaryLocaleList'
    String secondaryLocalesString = selectedVoiceMap['supportedLanguages'] as String? ?? '';
    
    List<String> languages = [];
    if (primaryLocale.isNotEmpty) {
      languages.add(primaryLocale);
    }
    if (secondaryLocalesString.isNotEmpty) {
      languages.addAll(secondaryLocalesString.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty));
    }
    
    // Remove duplicates and ensure the primary locale (if valid) is first
    _languagesForSelectedVoice = languages.toSet().toList();
    if (primaryLocale.isNotEmpty && _languagesForSelectedVoice.contains(primaryLocale)) {
        _languagesForSelectedVoice.remove(primaryLocale);
        _languagesForSelectedVoice.insert(0, primaryLocale);
    } else if (primaryLocale.isEmpty && _languagesForSelectedVoice.isNotEmpty) {
        // If primary locale was empty but we have other languages, make sure one is selected
    } else if (primaryLocale.isEmpty && _languagesForSelectedVoice.isEmpty) {
        // No languages reported at all. This case should be handled (e.g. disable language selection)
        // Or, if API guarantees 'locale' is always present, this is less of a concern.
    }


    // If the current _selectedLanguageCode is not in the new list, reset it (or set to primary)
    if (!_languagesForSelectedVoice.contains(_selectedLanguageCode)) {
      _selectedLanguageCode = _languagesForSelectedVoice.isNotEmpty ? _languagesForSelectedVoice.first : null;
    }
  }


  Future<void> _saveProfile() async {
    if (_formKey.currentState!.validate()) {
      _formKey.currentState!.save(); // Trigger onSaved for FormFields

      if (_selectedVoiceName == null || _selectedLanguageCode == null) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please select a voice and language.')),
        );
        return;
      }
      
      setState(() {
        _isSaving = true;
      });

      final profileData = UserProfile(
        id: widget.existingProfile?.id,
        name: _nameController.text,
        voiceName: _selectedVoiceName!,
        languageCode: _selectedLanguageCode!,
        speechRate: _speechRate,
        pitch: _pitch,
      );

      try {
        if (_isEditMode) {
          await widget.profileService.updateProfile(profileData);
        } else {
          await widget.profileService.createProfile(profileData);
        }
        Navigator.of(context).pop(true); // Pop with a true result to indicate success
      } catch (e) {
        print("Error saving profile: $e");
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to save profile: $e')),
        );
      } finally {
        setState(() {
          _isSaving = false;
        });
      }
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_isEditMode ? 'Edit Profile' : 'Create Profile'),
        actions: [
          IconButton(
            icon: const Icon(Icons.save),
            onPressed: _isSaving ? null : _saveProfile,
          ),
        ],
      ),
      body: _isLoadingVoices
          ? const Center(child: CircularProgressIndicator(key: ValueKey('loading_voices')))
          : _isSaving
              ? const Center(child: CircularProgressIndicator(key: ValueKey('saving_profile')))
              : SingleChildScrollView(
                  padding: const EdgeInsets.all(16.0),
                  child: Form(
                    key: _formKey,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        TextFormField(
                          controller: _nameController,
                          decoration: const InputDecoration(labelText: 'Profile Name'),
                          validator: (value) {
                            if (value == null || value.isEmpty) {
                              return 'Please enter a profile name';
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 20),

                        // Voice Selection Dropdown
                        DropdownButtonFormField<String>(
                          value: _selectedVoiceName,
                          decoration: const InputDecoration(labelText: 'Voice'),
                          items: _availableVoices.map((Map<String, dynamic> voiceMap) {
                            return DropdownMenuItem<String>(
                              value: voiceMap['name'] as String?, // 'name' is the ShortName
                              child: Text(voiceMap['displayName'] as String? ?? 'Unknown Voice'),
                            );
                          }).toList(),
                          onChanged: (String? newValue) {
                            setState(() {
                              _selectedVoiceName = newValue;
                              _selectedLanguageCode = null; // Reset language when voice changes
                              if (newValue != null) {
                                _updateLanguagesForVoice(newValue);
                              } else {
                                _languagesForSelectedVoice = [];
                              }
                            });
                          },
                          validator: (value) => value == null ? 'Please select a voice' : null,
                        ),
                        const SizedBox(height: 20),

                        // Language Selection Dropdown (conditionally enabled)
                        if (_selectedVoiceName != null && _languagesForSelectedVoice.isNotEmpty)
                          DropdownButtonFormField<String>(
                            value: _selectedLanguageCode,
                            decoration: const InputDecoration(labelText: 'Language'),
                            items: _languagesForSelectedVoice.map((String langCode) {
                              return DropdownMenuItem<String>(
                                value: langCode,
                                child: Text(langCode), // Consider mapping to display names if available
                              );
                            }).toList(),
                            onChanged: (String? newValue) {
                              setState(() {
                                _selectedLanguageCode = newValue;
                              });
                            },
                             validator: (value) => value == null ? 'Please select a language' : null,
                          )
                        else if (_selectedVoiceName != null && _languagesForSelectedVoice.isEmpty)
                            const Text("No specific languages listed for this voice, will use its default."),

                        const SizedBox(height: 20),
                        Text('Speech Rate: ${_speechRate.toStringAsFixed(1)}'),
                        Slider(
                          value: _speechRate,
                          min: 0.5,
                          max: 2.0,
                          divisions: 15, // (2.0 - 0.5) / 0.1 = 15
                          label: _speechRate.toStringAsFixed(1),
                          onChanged: (double value) {
                            setState(() {
                              _speechRate = value;
                            });
                          },
                        ),
                        const SizedBox(height: 20),
                        Text('Pitch: ${_pitch.toStringAsFixed(1)}'),
                        Slider(
                          value: _pitch,
                          min: 0.5,
                          max: 2.0, // Adjusted max to 2.0 as per UserProfile model
                          divisions: 15, // (2.0 - 0.5) / 0.1 = 15
                          label: _pitch.toStringAsFixed(1),
                          onChanged: (double value) {
                            setState(() {
                              _pitch = value;
                            });
                          },
                        ),
                        const SizedBox(height: 30),
                        // Save button also in AppBar
                        // Center(
                        //   child: ElevatedButton(
                        //     onPressed: _isSaving ? null : _saveProfile,
                        //     child: const Text('Save Profile'),
                        //   ),
                        // ),
                      ],
                    ),
                  ),
                ),
    );
  }
}

// Assuming Voice model looks something like this:
// Needs to be in a separate file e.g. models/voice_model.dart
// class Voice {
//   final String name; // ShortName from Azure
//   final String locale; // Primary locale
//   final String displayName;
//   final String gender;
//   final String supportedLanguages; // Comma-separated list of other locales
//
//   Voice({
//     required this.name,
//     required this.locale,
//     required this.displayName,
//     required this.gender,
//     this.supportedLanguages = '',
//   });
//
//   factory Voice.fromMap(Map<String, dynamic> map) {
//     return Voice(
//       name: map['name'] ?? '',
//       locale: map['locale'] ?? '',
//       displayName: map['displayName'] ?? map['name'] ?? '', // fallback for displayName
//       gender: map['gender'] ?? '',
//       supportedLanguages: map['supportedLanguages'] ?? '',
//     );
//   }
// }
// Make sure UserProfile is imported:
// import '../models/user_profile.dart';
// And ProfileService:
// import '../services/profile_service.dart';
// And VoiceService:
// import '../services/voice_service.dart';
