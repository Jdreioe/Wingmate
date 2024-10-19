package com.hoejmoseit.wingman.wingmanapp.database;

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

    @Query("SELECT * FROM SpeechItem WHERE parentId = :folderId")
    List<SpeechItem> getAllItemsInFolder(int folderId);

    @Query("SELECT * FROM SpeechItem WHERE parentId IS NULL")
    List<SpeechItem> getAllRootItems();

    @Query("SELECT * FROM SpeechItem")
    List<SpeechItem> getAll();

    @Delete
    void deleteItems(List<SpeechItem> items);

    @Query("SELECT * FROM SpeechItem WHERE id = :id")
    SpeechItem getItemById(int id);


    // ... other DAO methods
}