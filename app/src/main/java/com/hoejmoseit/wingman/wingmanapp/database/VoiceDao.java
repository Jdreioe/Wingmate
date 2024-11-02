package com.hoejmoseit.wingman.wingmanapp.database;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface VoiceDao {
    @Insert
    void insertAll(List<VoiceItem> voices);

    @Query("SELECT * FROM VoiceItem WHERE createdAt >= :expirationTime")
    List<VoiceItem> getAllVoices(long expirationTime);
    @Insert
    void insert(VoiceItem voiceItem);
    @Query("DELETE FROM VoiceItem")
    void deleteAll();
}

