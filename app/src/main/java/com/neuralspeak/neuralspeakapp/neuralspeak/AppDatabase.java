package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {SpeechItem.class, VoiceItem.class}, version = 4)
public abstract class AppDatabase extends RoomDatabase {
    public abstract SpeechItemDao speechItemDao();
    public abstract VoiceDao voiceDao();


}

