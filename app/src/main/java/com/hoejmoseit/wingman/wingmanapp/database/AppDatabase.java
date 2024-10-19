package com.hoejmoseit.wingman.wingmanapp.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

@Database(entities = {SpeechItem.class, VoiceItem.class, SaidTextItem.class}, version = 9, exportSchema = false )
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {
    public abstract SpeechItemDao speechItemDao();
    public abstract VoiceDao voiceDao();
    public  abstract SaidTextDao saidTextDao();
}