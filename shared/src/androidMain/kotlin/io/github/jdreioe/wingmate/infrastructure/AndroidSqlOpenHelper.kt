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
                recording_path TEXT,
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
        // Legacy migration: move phrases flagged as categories into categories table and delete them from phrases
        try {
            db.beginTransaction()
            val cursor = db.rawQuery("SELECT id, COALESCE(NULLIF(name,''), NULLIF(text,'')) FROM phrases WHERE is_category = 1", null)
            val legacy = mutableListOf<Pair<String, String?>>()
            while (cursor.moveToNext()) {
                legacy += cursor.getString(0) to cursor.getString(1)
            }
            cursor.close()
            if (legacy.isNotEmpty()) {
                // Determine next ordering
                var next = 0
                val ordCur = db.rawQuery("SELECT COALESCE(MAX(ordering), -1) FROM categories", null)
                if (ordCur.moveToFirst()) next = ordCur.getInt(0) + 1
                ordCur.close()
                val insert = db.compileStatement("INSERT OR IGNORE INTO categories(id, name, selectedLanguage, ordering) VALUES (?,?,?,?)")
                legacy.forEachIndexed { idx, (id, name) ->
                    insert.bindString(1, id)
                    insert.bindString(2, name ?: id)
                    insert.bindNull(3)
                    insert.bindLong(4, (next + idx).toLong())
                    insert.executeInsert()
                }
                db.delete("phrases", "is_category = 1", emptyArray())
            }
            db.setTransactionSuccessful()
        } catch (_: Throwable) {
            // swallow; non-critical
        } finally {
            try { db.endTransaction() } catch (_: Throwable) {}
        }

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

        // Persist full voice catalog as a separate single-row table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS voice_catalog (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                list TEXT
            );
            """.trimIndent()
        )

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
        // Migrations by version
        if (oldVersion < 2 && newVersion >= 2) {
            // Rebuild voices table to enforce single-row (id=1) and clean any invalid rows
            db.execSQL("DROP TABLE IF EXISTS voices")
            // Ensure fresh schema including voices and voice_catalog
            ensureTables(db)
        }
        if (oldVersion < 3 && newVersion >= 3) {
            // Add recording_path to phrases
            try {
                db.execSQL("ALTER TABLE phrases ADD COLUMN recording_path TEXT")
            } catch (_: Throwable) {
                // ignore if already exists
            }
        }
    }

    companion object {
        private const val DB_VERSION = 3
    }
}
