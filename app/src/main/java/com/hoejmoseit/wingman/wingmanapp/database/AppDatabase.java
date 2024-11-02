package com.hoejmoseit.wingman.wingmanapp.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.work.impl.AutoMigration_19_20;
//TODO: skriv migration script

@Database(entities = {SpeechItem.class, VoiceItem.class, SaidTextItem.class}, version = 10, exportSchema = true)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {
    public abstract SpeechItemDao speechItemDao();

    public abstract VoiceDao voiceDao();

    public abstract SaidTextDao saidTextDao();


    public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Add the createdAt column to the VoiceItem table
            database.execSQL("CREATE TABLE VoiceItem_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "saidText TEXT, " +
                    "date INTEGER, " +
                    "voiceName TEXT, " +
                    "pitch REAL, " +
                    "speed REAL, " +
                    "audioFilePath TEXT, " +
                    "createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now')))");

            database.execSQL("INSERT INTO VoiceItem_new (id, saidText, date, voiceName, pitch, speed, audioFilePath) " +
                    "SELECT id, saidText, date, voiceName, pitch, speed, audioFilePath FROM VoiceItem");

            database.execSQL("DROP TABLE VoiceItem");
            database.execSQL("ALTER TABLE VoiceItem_new RENAME TO VoiceItem");

            // SaidTextItem migration (if needed - add createdAt if not present)
            database.execSQL("ALTER TABLE SaidTextItem ADD COLUMN createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now'))");

            // SpeechItem migration (if needed - add createdAt if not present)
            database.execSQL("ALTER TABLE SpeechItem ADD COLUMN createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now'))");
        }
    };

}






