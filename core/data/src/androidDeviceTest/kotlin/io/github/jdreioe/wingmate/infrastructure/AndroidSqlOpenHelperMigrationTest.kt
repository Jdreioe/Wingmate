package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSqlOpenHelperMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "wingmate-migration-test.db"

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun upgradeTurnsFlatCategoriesIntoFolderPhrasesThatKeepTheirMembers() {
        createVersion8Database()

        AndroidSqlOpenHelper(context, dbName).readableDatabase.use { db ->
            val categoriesTable = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'categories'",
                null,
            ).use { it.moveToFirst() }
            assertFalse(categoriesTable)

            val rows = db.rawQuery(
                "SELECT id, text, parent_id, linked_board_id FROM phrases ORDER BY ordering",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(List(4) { cursor.getString(it) })
                    }
                }
            }
            // Folder-Phrases link to themselves; the member Phrase still points at its Category.
            assertEquals(
                listOf(
                    listOf("apple", "Apple", "cat-food", null),
                    listOf("cat-drinks", "Drinks", null, "cat-drinks"),
                    listOf("cat-food", "Food", null, "cat-food"),
                ),
                rows,
            )
        }
    }

    private fun createVersion8Database() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { db ->
            db.execSQL(
                """
                CREATE TABLE phrases (
                    id TEXT PRIMARY KEY, text TEXT, name TEXT, background_color TEXT, image_url TEXT,
                    parent_id TEXT, linked_board_id TEXT, is_category INTEGER DEFAULT 0, created_at INTEGER,
                    recording_path TEXT, is_hidden INTEGER NOT NULL DEFAULT 0, ordering INTEGER, puck_action TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT NOT NULL, selectedLanguage TEXT, ordering INTEGER DEFAULT 0)"
            )
            db.execSQL("INSERT INTO phrases(id, text, parent_id, created_at, ordering) VALUES ('apple', 'Apple', 'cat-food', 1, 0)")
            db.execSQL("INSERT INTO categories(id, name, ordering) VALUES ('cat-food', 'Food', 1)")
            db.execSQL("INSERT INTO categories(id, name, ordering) VALUES ('cat-drinks', ' Drinks ', 0)")
            db.version = 8
        }
    }
}
