import 'dart:io' as io show Platform;
import 'package:flutter/foundation.dart' show kIsWeb;

bool get isIOS => !kIsWeb && io.Platform.isIOS;
bool get isLinux => !kIsWeb && io.Platform.isLinux;
bool get isAndroid => !kIsWeb && io.Platform.isAndroid;
