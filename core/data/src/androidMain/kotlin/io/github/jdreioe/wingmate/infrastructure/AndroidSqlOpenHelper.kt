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
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS phrases (
                id TEXT PRIMARY KEY,
                text TEXT,
                name TEXT,
                background_color TEXT,
                image_url TEXT,
                parent_id TEXT,
                linked_board_id TEXT,
                created_at INTEGER,
                recording_path TEXT,
                is_hidden INTEGER NOT NULL DEFAULT 0,
                ordering INTEGER,
                puck_action TEXT
            );
        """.trimIndent())

        // CREATE TABLE IF NOT EXISTS does not add columns to an existing table.
        // Repair databases whose user_version was advanced without every column.
        ensureColumn(db, "phrases", "recording_path", "TEXT")
        ensureColumn(db, "phrases", "image_url", "TEXT")
        ensureColumn(db, "phrases", "linked_board_id", "TEXT")
        ensureColumn(db, "phrases", "puck_action", "TEXT")
        ensureColumn(db, "phrases", "is_hidden", "INTEGER NOT NULL DEFAULT 0")

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

        // Pronunciation dictionary (single-row JSON list)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pronunciation_dictionary (
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
                primary_language TEXT,
                visible_in_history INTEGER NOT NULL DEFAULT 1
            );
        """.trimIndent())
        ensureColumn(db, "said_texts", "visible_in_history", "INTEGER NOT NULL DEFAULT 1")
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
        if (oldVersion < 5 && newVersion >= 5) {
            try {
                db.execSQL("ALTER TABLE phrases ADD COLUMN image_url TEXT")
            } catch (_: Throwable) {
                // ignore
            }
            try {
                db.execSQL("ALTER TABLE phrases ADD COLUMN linked_board_id TEXT")
            } catch (_: Throwable) {
                // ignore
            }
        }
        if (oldVersion < 6 && newVersion >= 6) {
            try {
                db.execSQL("ALTER TABLE phrases ADD COLUMN puck_action TEXT")
            } catch (_: Throwable) {
                // ignore if column already exists
            }
        }
        if (oldVersion < 7 && newVersion >= 7) {
            ensureColumn(db, "phrases", "is_hidden", "INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8 && newVersion >= 8) {
            ensureColumn(db, "said_texts", "visible_in_history", "INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 9 && newVersion >= 9) {
            convertFlatCategoriesToFolderPhrases(db)
        }
    }

    /**
     * Wingmate 1.1.0 and earlier stored Categories in a separate `categories` table (and, before
     * that, as `is_category` phrase rows). Categories are now folder-Phrases: phrase rows whose
     * `linked_board_id` is their own id. Convert both legacy shapes in place, keeping ids so
     * `parent_id` membership survives, and append them after existing phrases in their old order.
     * Mirrors the flat-category conversion in CompleteBackupManager's restore.
     */
    private fun convertFlatCategoriesToFolderPhrases(db: SQLiteDatabase) {
        if (hasColumn(db, "phrases", "is_category")) {
            db.execSQL("UPDATE phrases SET linked_board_id = id, is_category = 0 WHERE is_category = 1")
        }
        if (!hasTable(db, "categories")) return
        var ordering = db.rawQuery("SELECT COALESCE(MAX(ordering), -1) + 1 FROM phrases", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val orderBy = if (hasColumn(db, "categories", "ordering")) "ordering, rowid" else "rowid"
        val createdAt = System.currentTimeMillis()
        val insert = db.compileStatement(
            "INSERT OR IGNORE INTO phrases(id, text, linked_board_id, created_at, is_hidden, ordering) VALUES (?, ?, ?, ?, 0, ?)"
        )
        db.rawQuery("SELECT id, name FROM categories ORDER BY $orderBy", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val name = cursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Category"
                insert.bindString(1, id)
                insert.bindString(2, name)
                insert.bindString(3, id)
                insert.bindLong(4, createdAt)
                insert.bindLong(5, ordering++)
                insert.executeInsert()
            }
        }
        db.execSQL("DROP TABLE categories")
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

    private fun ensureColumn(
        db: SQLiteDatabase,
        table: String,
        column: String,
        declaration: String
    ) {
        if (!hasColumn(db, table, column)) db.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
            false
        }

    companion object {
        private const val DB_VERSION = 9
    }
}
