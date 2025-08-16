package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class AndroidSqlOpenHelper(
    context: Context,
    name: String = "wingmate.db",
    version: Int = DB_VERSION,
) : SQLiteOpenHelper(context, name, null, version) {

    override fun onCreate(db: SQLiteDatabase) {
        // Create all tables (initial creation)
        ensureTables(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Ensure tables exist for older DBs that lack newer tables
        ensureTables(db)
    }

    private fun ensureTables(db: SQLiteDatabase) {
        // Mirror desktop schema minimal subset
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS phrases (
                id TEXT PRIMARY KEY,
                text TEXT,
                name TEXT,
                background_color TEXT,
                parent_id TEXT,
                is_category INTEGER DEFAULT 0,
                created_at INTEGER,
                ordering INTEGER
            );
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS categories (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                selectedLanguage TEXT,
                ordering INTEGER DEFAULT 0
            );
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS configs (
                id TEXT PRIMARY KEY,
                json TEXT NOT NULL
            );
        """.trimIndent())

        // Persist selected voice (single row)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS voices (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                data TEXT
            );
        """.trimIndent())

        // UI settings storage (single row)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ui_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                data TEXT
            );
        """.trimIndent())

        // Said texts
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS said_texts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date INTEGER,
                said_text TEXT,
                voice_name TEXT,
                pitch REAL,
                speed REAL,
                audio_file_path TEXT,
                created_at INTEGER,
                position INTEGER,
                primary_language TEXT
            );
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Simple migration strategy: for now, only handle version bumps gracefully.
        // Future migrations should be added here mirroring desktop migrations.
        if (oldVersion < 2 && newVersion >= 2) {
            // example migration placeholder
        }
    }

    companion object {
        private const val DB_VERSION = 1
    }
}
