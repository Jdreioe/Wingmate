package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SpeechItemDao {

    @Insert
    void insertItems(SpeechItem... speechItems);

    @Insert
    long insertItem(SpeechItem speechItem);

    @Query("SELECT * FROM SpeechItem WHERE folderId = :folderId")
    List<SpeechItem> findChildren(int folderId);

    @Query("SELECT * FROM SpeechItem WHERE folderId IS NULL")
    List<SpeechItem> getAllRootItems();

    @Query("SELECT * FROM SpeechItem")
    List<SpeechItem> getAll();

    @Delete
    void deleteItems(List<SpeechItem> items);

    // ... other DAO methods
}