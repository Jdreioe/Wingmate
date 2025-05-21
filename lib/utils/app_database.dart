import 'dart:io' show Platform;
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:sqflite/sqflite.dart' as sqflite;
import 'package:path/path.dart';

class AppDatabase {
  static final AppDatabase _instance = AppDatabase._internal();
  static const int _databaseVersion = 12; // Update the version number
  static Database? _database;

  // Singleton pattern for a shared database instance.
  factory AppDatabase() {
    return _instance;
  }

  AppDatabase._internal();

  Future<Database> get database async {
    if (_database != null) return _database!;

    _database = await _initDatabase();
    return _database!;
  }

  // Initializes the database, including FFI for desktop/web platforms.
  Future<Database> _initDatabase() async {
    if (Platform.isWindows || Platform.isLinux || Platform.isMacOS || kIsWeb) {
      // Initialize FFI for desktop/web
      sqfliteFfiInit();
      print("PC/Mac/Web");
      sqflite.databaseFactory = databaseFactoryFfi;
    }
    final databasesPath = await getDatabasesPath();
    final path = join(databasesPath, 'wingman_database.db');

    return await openDatabase(
      path,
      version: 12, // incremented database version
      onCreate: _onCreate,
      onUpgrade: _onUpgrade,
    );
  }

  // Creates necessary tables for storing speech items, voices, and said texts.
  Future<void> _onCreate(Database db, int version) async {
    await db.execute('''
      CREATE TABLE SpeechItem (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT,
        text TEXT,
        isFolder INTEGER, 
        parentId INTEGER,
        createdAt INTEGER,
        position INTEGER,
        filePath TEXT,
        voiceName TEXT,
        pitch REAL,
        selectedLanguage TEXT,
        rateForSsml REAL,
        pitchForSsml REAL
      );
    ''');

    await db.execute('''
        CREATE TABLE VoiceItem (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT,
          supportedLanguages TEXT,
          gender TEXT,
          locale TEXT,
          createdAt INTEGER,
          displayName TEXT
        )
        ''');

    await db.execute('''
      CREATE TABLE SaidTextItem (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        date INTEGER,
        saidText TEXT,
        voiceName TEXT,
        pitch REAL,
        speed REAL,
        audioFilePath TEXT,
        createdAt INTEGER,
        position INTEGER,
        primaryLanguage TEXT
      )
    ''');

    await db.execute('''
      CREATE TABLE user_profiles (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        voiceName TEXT NOT NULL,
        languageCode TEXT NOT NULL,
        speechRate REAL NOT NULL,
        pitch REAL NOT NULL
      )
    ''');
  }

  // Handles database schema upgrades (e.g., adding new columns).
  Future<void> _onUpgrade(Database db, int oldVersion, int newVersion) async {
    print(
        'Upgrading database from version $oldVersion to $newVersion'); // Add this line
    if (oldVersion < 2) {
      print('Applying migration to add displayName'); // Add this line
      // Add the displayName and locale columns
      try {
        await db.execute('''
        ALTER TABLE VoiceItem ADD COLUMN displayName TEXT;
      ''');
      } catch (e) {
        print('Migration error: $e'); // Log any errors during migration
      }
    } 
    if (oldVersion < 3) {
      await db.execute('ALTER TABLE SaidTextItem ADD COLUMN position INTEGER;');
    }
    if (oldVersion < 10) {
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN filePath TEXT;');
    }
    if (oldVersion < 11) {
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN voiceName TEXT;');
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN pitch REAL;');
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN selectedLanguage TEXT;');
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN rateForSsml REAL;');
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN pitchForSsml REAL;');
    }

    // Add other migration blocks for future versions here. For example:
    // if (oldVersion < 3) {
    //   // Add another column or table
    // }
  }
}