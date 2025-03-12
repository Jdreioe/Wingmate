import 'dart:io';
import 'package:flutter/material.dart';
import 'package:whisper_library_dart/whisper_library_dart.dart';
import 'package:record/record.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:audio_session/audio_session.dart';
import 'package:path_provider/path_provider.dart';
import 'package:flutter/services.dart';
import 'package:whisper_flutter_new/whisper_flutter_new.dart';
import 'package:path/path.dart' as path;

class SpeechToTextPage extends StatefulWidget {
  @override
  _SpeechToTextPageState createState() => _SpeechToTextPageState();
}

class _SpeechToTextPageState extends State<SpeechToTextPage> {
  bool _isListening = false;
  String _text = 'Press the button and start speaking';
  final record = AudioRecorder();
  late String _recordingPath;
  List<InputDevice> _inputDevices = [];
  InputDevice? _selectedDevice;
  final TextEditingController _textController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _initializeWhisper();
    _loadInputDevices();
  }

  Future<String> getLibraryPath() async {
    final directory = await getApplicationDocumentsDirectory();
    return path.join(directory.path, 'whisper_library');
  }

  Future<void> _initializeWhisper() async {
    // Initialize Whisper library
    final Whisper whisper = Whisper(
      model: WhisperModel.base,
      downloadHost: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main");

    final String? whisperVersion = await whisper.getVersion();
    print(whisperVersion);
  }

  Future<void> _loadInputDevices() async {
    final devices = await record.listInputDevices();
    setState(() {
      _inputDevices = devices;
      if (_inputDevices.isNotEmpty) {
        _selectedDevice = _inputDevices.first;
      }
    });
  }

  Future<void> _listen() async {
    if (!_isListening) {
      if (!await record.hasPermission()) {
        var status = await Permission.microphone.request();
        if (status != PermissionStatus.granted) {
          setState(() {
            _text = "Microphone permission not granted";
          });
          return;
        }
      }

      setState(() => _isListening = true);

      await record.start(RecordConfig(
        encoder: AudioEncoder.wav,
        bitRate: 128000,
        sampleRate: 16000,
        device: _selectedDevice,
      ), path: "audio.wav",);
    } else {
      setState(() => _isListening = false);
      final path = await record.stop();
      if (path != null) {
        _processAudioFile(path);
      }
    }
  }

  Future<void> _processAudioFile(String filePath) async {
    try {
      // Begin whisper transcription
      final Whisper whisper = Whisper(
        model: WhisperModel.base,
        downloadHost: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
      );

      final String transcription = (await whisper.transcribe(
        transcribeRequest: TranscribeRequest(
          audio: filePath,
          isTranslate: true,
          isNoTimestamps: false,
          splitOnWord: true,
        ),
      )) as String;
      setState(() {
        _text = transcription;
      });
    } catch (e) {
      setState(() {
        _text = "Error processing audio file: $e";
      });
    }
  }

  @override
  void dispose() {
    record.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Speech to Text'),
      ),
      body: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (!_inputDevices.isNotEmpty)
            DropdownButton<InputDevice>(
              value: _selectedDevice,
              onChanged: (device) {
                setState(() {
                  _selectedDevice = device;
                });
              },
              items: _inputDevices.map((device) {
                return DropdownMenuItem(
                  value: device,
                  child: Text(device.label),
                );
              }).toList(),
            ),
          Center(
            child: Text(
              _text,
              style: TextStyle(fontSize: 24.0),
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _listen,
        child: Icon(_isListening ? Icons.mic : Icons.mic_none),
      ),
    );
  }
}