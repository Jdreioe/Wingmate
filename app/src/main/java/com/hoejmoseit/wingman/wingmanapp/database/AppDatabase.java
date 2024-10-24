package com.hoejmoseit.wingman.wingmanapp.database;

import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
//TODO: skriv migration script

@Database(entities = {SpeechItem.class, VoiceItem.class, SaidTextItem.class}, version = 9, exportSchema = true)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {
    public abstract SpeechItemDao speechItemDao();
    public abstract VoiceDao voiceDao();
    public  abstract SaidTextDao saidTextDao();
}


