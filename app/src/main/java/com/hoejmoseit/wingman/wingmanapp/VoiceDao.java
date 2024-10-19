package com.hoejmoseit.wingman.wingmanapp;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface VoiceDao {
    @Insert
    void insertAll(List<VoiceItem> voices);

    @Query("SELECT * FROM VoiceItem ")
    List<VoiceItem> getAllVoices();
    @Insert
    void insert(VoiceItem voiceItem);
}

