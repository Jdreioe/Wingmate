import 'package:flutter/material.dart';
import 'dart:io' show Platform;
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:sqflite/sqflite.dart' as sqflite;
import 'package:path/path.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

class AppDatabase {
  static final AppDatabase _instance = AppDatabase._internal();
  static Database? _database;

  factory AppDatabase() {
    return _instance;
  }

  AppDatabase._internal();

  Future<Database> get database async {
    if (_database != null) return _database!;

    _database = await _initDatabase();
    return _database!;
  }

  Future<Database> _initDatabase() async {
    if (Platform.isWindows || Platform.isLinux || Platform.isMacOS || kIsWeb) {
      // Initialize FFI for desktop/web
      sqfliteFfiInit();
      print("PC/Mac/Web");
      sqflite.databaseFactory = databaseFactoryFfi;
    }
    final databasesPath = await getDatabasesPath();
    final path = join(databasesPath, 'wingman_database.db');

    return await openDatabase(path,
        version: 2, onCreate: _onCreate, onUpgrade: _onUpgrade);
  }

  Future<void> _onCreate(Database db, int version) async {
    await db.execute('''
      CREATE TABLE SpeechItem (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT,
        text TEXT,
        isFolder INTEGER, 
        parentId INTEGER,
        createdAt INTEGER
      )
    ''');

    await db.execute('''
      CREATE TABLE VoiceItem (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT,
        supportedLanguages TEXT,
        gender TEXT,
        primarylanguage TEXT,
        createdAt INTEGER
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
        createdAt INTEGER
      )
    ''');
  }

  Future<void> _onUpgrade(Database db, int oldVersion, int newVersion) async {
    print(
        'Upgrading database from version $oldVersion to $newVersion'); // Add this line
    if (oldVersion < 2) {
      print(
          'Applying migration to add displayName and locale'); // Add this line
      // Add the displayName and locale columns
      try {
        await db.execute('''
        ALTER TABLE VoiceItem ADD COLUMN displayName TEXT;
      ''');
        await db.execute('''
        ALTER TABLE VoiceItem ADD COLUMN locale TEXT;
      ''');
      } catch (e) {
        print('Migration error: $e'); // Log any errors during migration
      }
    } // Add other migration blocks for future versions here. For example:
    // if (oldVersion < 3) {
    //   // Add another column or table
    // }
  }
}
