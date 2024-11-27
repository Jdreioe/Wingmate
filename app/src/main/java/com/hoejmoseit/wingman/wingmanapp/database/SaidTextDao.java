package com.hoejmoseit.wingman.wingmanapp.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface SaidTextDao {
    @Query("SELECT * FROM SaidTextItem WHERE saidText = :text LIMIT 1")
    SaidTextItem getByText(String text);
    @Query("SELECT * FROM SaidTextItem WHERE saidText = :text ")
    List<SaidTextItem> getAllByText(String text);


    @Insert
    Long insertHistorik(SaidTextItem SaidTextItem);
    @Delete
    void deleteHistorik(SaidTextItem SaidTextItem);
    @Update(onConflict = OnConflictStrategy.REPLACE)
    void updateHistorik(SaidTextItem SaidTextItem);
    @Query("SELECT * FROM SaidTextItem")
    List<SaidTextItem> getAll();
}
