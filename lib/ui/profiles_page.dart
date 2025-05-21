import 'package:flutter/material.dart';
import '../models/user_profile.dart';
import '../services/profile_service.dart';
import '../services/voice_service.dart'; // Added import for VoiceService
import 'edit_profile_page.dart'; // Added import for EditProfilePage

class ProfilesPage extends StatefulWidget {
  final ProfileService profileService;
  final VoiceService voiceService; // Added VoiceService

  const ProfilesPage({
    Key? key,
    required this.profileService,
    required this.voiceService, // Added to constructor
  }) : super(key: key);

  @override
  _ProfilesPageState createState() => _ProfilesPageState();
}

class _ProfilesPageState extends State<ProfilesPage> {
  List<UserProfile> _profiles = [];
  int? _activeProfileId;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadProfileData();
  }

  Future<void> _loadProfileData() async {
    setState(() {
      _isLoading = true;
    });
    try {
      final profiles = await widget.profileService.getProfiles();
      final activeProfile = await widget.profileService.getActiveProfile();
      setState(() {
        _profiles = profiles;
        _activeProfileId = activeProfile?.id;
        _isLoading = false;
      });
    } catch (e) {
      // Handle error, e.g., show a snackbar
      print("Error loading profiles: $e");
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _setActiveProfile(int profileId) async {
    try {
      await widget.profileService.setActiveProfile(profileId);
      _loadProfileData(); // Reload to reflect the change
    } catch (e) {
      // Handle error
      print("Error setting active profile: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('User Profiles'),
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : ListView.builder(
              itemCount: _profiles.length,
              itemBuilder: (context, index) {
                final profile = _profiles[index];
                final isActive = profile.id == _activeProfileId;
                return ListTile(
                  title: Text(profile.name),
                  subtitle: Text('Voice: ${profile.voiceName}, Lang: ${profile.languageCode}'),
                  tileColor: isActive ? Colors.blue.withOpacity(0.1) : null,
                  onTap: () {
                    if (profile.id != null) {
                      _setActiveProfile(profile.id!);
                    }
                  },
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: const Icon(Icons.edit),
                        onPressed: () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => EditProfilePage(
                                profileService: widget.profileService,
                                voiceService: widget.voiceService,
                                existingProfile: profile,
                              ),
                            ),
                          ).then((result) {
                            // If the page returns true, it means a profile was saved.
                            if (result == true) {
                              _loadProfileData(); // Refresh the list
                            }
                          });
                        },
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete),
                        onPressed: () async {
                          // Confirm deletion
                          final confirm = await showDialog<bool>(
                            context: context,
                            builder: (BuildContext context) {
                              return AlertDialog(
                                title: const Text('Confirm Delete'),
                                content: Text('Are you sure you want to delete profile "${profile.name}"?'),
                                actions: <Widget>[
                                  TextButton(
                                    onPressed: () => Navigator.of(context).pop(false),
                                    child: const Text('Cancel'),
                                  ),
                                  TextButton(
                                    onPressed: () => Navigator.of(context).pop(true),
                                    child: const Text('Delete'),
                                  ),
                                ],
                              );
                            },
                          );

                          if (confirm == true && profile.id != null) {
                            try {
                              await widget.profileService.deleteProfile(profile.id!);
                              _loadProfileData(); // Refresh list
                               ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(content: Text('Profile "${profile.name}" deleted.')),
                              );
                            } catch (e) {
                               print("Error deleting profile: $e");
                               ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(content: Text('Failed to delete profile: $e')),
                              );
                            }
                          }
                        },
                      ),
                    ],
                  ),
                );
              },
            ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (context) => EditProfilePage(
                profileService: widget.profileService,
                voiceService: widget.voiceService,
                // No existingProfile means create mode
              ),
            ),
          ).then((result) {
             if (result == true) {
              _loadProfileData(); // Refresh the list
            }
          });
        },
        child: const Icon(Icons.add),
        tooltip: 'Create New Profile',
      ),
    );
  }
}
