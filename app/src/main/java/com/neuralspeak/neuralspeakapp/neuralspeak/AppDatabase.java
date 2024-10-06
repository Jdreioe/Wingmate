package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {SpeechItem.class}, version = 2)
public abstract class AppDatabase extends RoomDatabase {
    public abstract SpeechItemDao speechItemDao();
    public abstract VoiceDao voiceDao();


}

