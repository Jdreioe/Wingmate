import 'dart:io' show Platform;
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:sqflite/sqflite.dart' as sqflite;
import 'package:path/path.dart';
import 'package:flutter/material.dart'; // Added for Icons

class AppDatabase {
  static final AppDatabase _instance = AppDatabase._internal();
  static const int _databaseVersion = 22; // Update the version number
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
      version: _databaseVersion,
      onCreate: _onCreate,
      onUpgrade: _onUpgrade,
    );
  }

  // Creates necessary tables for storing speech items, voices, and said texts.
  Future<void> _onCreate(Database db, int version) async {
    await db.execute('''
      CREATE TABLE PhraseItem (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT,
        text TEXT,
        isCategory INTEGER, 
        parentId INTEGER,
        createdAt INTEGER,
        position INTEGER,
        filePath TEXT,
        voiceName TEXT,
        pitch REAL,
        selectedLanguage TEXT,
        rateForSsml REAL,
        pitchForSsml REAL,
        backgroundColor TEXT,
        labelColor TEXT,
        imagePath TEXT
      );
    ''');

    await db.execute('''
      CREATE TABLE CategoryItem (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        language TEXT NOT NULL,
        name TEXT NOT NULL,
        color TEXT,
        iconCodePoint INTEGER
      )
    ''');

    // Insert default categories
    await db.insert('CategoryItem', {'language': 'en-US', 'name': 'General', 'color': 'FF2196F3', 'iconCodePoint': Icons.home.codePoint}); // Blue
    await db.insert('CategoryItem', {'language': 'en-US', 'name': 'Greetings', 'color': 'FF4CAF50', 'iconCodePoint': Icons.waving_hand.codePoint}); // Green
    await db.insert('CategoryItem', {'language': 'en-US', 'name': 'Food', 'color': 'FFFFC107', 'iconCodePoint': Icons.fastfood.codePoint}); // Amber

    // Get the IDs of the newly inserted categories
    final List<Map<String, dynamic>> categories = await db.query('CategoryItem', columns: ['id', 'name']);
    final generalCategoryId = categories.firstWhere((cat) => cat['name'] == 'General')['id'];
    final greetingsCategoryId = categories.firstWhere((cat) => cat['name'] == 'Greetings')['id'];
    final foodCategoryId = categories.firstWhere((cat) => cat['name'] == 'Food')['id'];

    // Insert default phrases
    await db.insert('PhraseItem', {'name': 'Hello', 'text': 'Hello', 'isCategory': 0, 'parentId': greetingsCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFBBDEFB'}); // Light Blue
    await db.insert('PhraseItem', {'name': 'How are you?', 'text': 'How are you?', 'isCategory': 0, 'parentId': greetingsCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFBBDEFB'});
    await db.insert('PhraseItem', {'name': 'Thank you', 'text': 'Thank you', 'isCategory': 0, 'parentId': greetingsCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFBBDEFB'});
    await db.insert('PhraseItem', {'name': 'Yes', 'text': 'Yes', 'isCategory': 0, 'parentId': generalCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFC8E6C9'}); // Light Green
    await db.insert('PhraseItem', {'name': 'No', 'text': 'No', 'isCategory': 0, 'parentId': generalCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFFFCDD2'}); // Light Red
    await db.insert('PhraseItem', {'name': 'Please', 'text': 'Please', 'isCategory': 0, 'parentId': generalCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFE0F2F7'}); // Light Cyan
    await db.insert('PhraseItem', {'name': 'Water', 'text': 'Water', 'isCategory': 0, 'parentId': foodCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFB3E5FC'}); // Light Blue
    await db.insert('PhraseItem', {'name': 'Food', 'text': 'Food', 'isCategory': 0, 'parentId': foodCategoryId, 'createdAt': DateTime.now().millisecondsSinceEpoch, 'backgroundColor': 'FFB3E5FC'});

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

    await db.execute('''
      CREATE TABLE ui_settings (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        themeMode TEXT NOT NULL,
        fontSize REAL NOT NULL,
        fieldSize REAL NOT NULL,
        phraseFontSize REAL NOT NULL,
        phraseWidth REAL NOT NULL,
        phraseHeight REAL NOT NULL
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
    if (oldVersion < 13) {
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN backgroundColor TEXT;');
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN labelColor TEXT;');
    }
    if (oldVersion < 14) {
      await db.execute('ALTER TABLE SpeechItem ADD COLUMN imagePath TEXT;');
    }
    if (oldVersion < 17) {
      await db.execute('CREATE TABLE ui_settings (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, themeMode TEXT NOT NULL, fontSize REAL NOT NULL, fieldSize REAL NOT NULL, phraseFontSize REAL NOT NULL, phraseWidth REAL NOT NULL, phraseHeight REAL NOT NULL)');
    }
    if (oldVersion < 18) {
      // Rename SpeechItem table to PhraseItem
      await db.execute('ALTER TABLE SpeechItem RENAME TO PhraseItem;');
    }
    if (oldVersion < 19) {
      // Rename isFolder column to isCategory
      await db.execute('ALTER TABLE PhraseItem RENAME COLUMN isFolder TO isCategory;');
    }
    if (oldVersion < 20) {
      await db.execute('''
        CREATE TABLE CategoryItem (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          language TEXT NOT NULL,
          name TEXT NOT NULL,
          color TEXT,
          iconCodePoint INTEGER
        )
      ''');
    }

    // Add other migration blocks for future versions here. For example:
    // if (oldVersion < 3) {
    //   // Add another column or table
    // }
  }
}