package com.hoejmoseit.wingman.wingmanapp;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.Date;
@Entity(indices = {@Index("date")})

public class SaidTextItem {
    @PrimaryKey (autoGenerate = true)
    public int id;
    public Date date;
    public String saidText;

    public String voiceName;
    public String audioFilePath;
}