package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverter;
import androidx.room.TypeConverters;

@Database(entities = {SpeechItem.class, VoiceItem.class, SaidTextItem.class}, version = 7)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {
    public abstract SpeechItemDao speechItemDao();
    public abstract VoiceDao voiceDao();
    public  abstract SaidTextDao saidTextDao();



}

