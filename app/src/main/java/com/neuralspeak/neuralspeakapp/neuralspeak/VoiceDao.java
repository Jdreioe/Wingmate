package com.neuralspeak.neuralspeakapp.neuralspeak;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface VoiceDao {
    @Insert
    void insertAll(List<Voice> voices);

    @Query("SELECT * FROM Voice")
    List<Voice> getAllVoices();


}
