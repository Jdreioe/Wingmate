import 'dart:io';
import 'package:flutter/foundation.dart' show kIsWeb, debugPrint;
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as p;

/// Returns a suitable directory for persistent application data.
/// On Linux, if getApplicationDocumentsDirectory fails, it falls back to a
/// directory within the user's home directory.
Future<Directory> getPersistentStorageDirectory() async {
  if (kIsWeb) {
    throw UnsupportedError('Persistent storage is not directly accessible on web platform.');
  }

  try {
    final appDocumentsDir = await getApplicationDocumentsDirectory();
    debugPrint('[PlatformDirectory] Using application documents directory: ${appDocumentsDir.path}');
    return appDocumentsDir;
  } catch (e) {
    debugPrint('[PlatformDirectory] getApplicationDocumentsDirectory failed: $e');
    if (Platform.isLinux) {
      debugPrint('[PlatformDirectory] Falling back to user home directory for Linux.');
      final homeDir = Platform.environment['HOME'];
      if (homeDir != null) {
        final appDataDir = Directory(p.join(homeDir, '.local', 'share', 'wingmate'));
        if (!await appDataDir.exists()) {
          await appDataDir.create(recursive: true);
        }
        debugPrint('[PlatformDirectory] Using fallback Linux directory: ${appDataDir.path}');
        return appDataDir;
      }
    }
    // If all else fails, or not Linux, rethrow the original exception
    rethrow;
  }
}
