package com.hoejmoseit.wingman.wingmanapp.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class VoiceItem {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String name;
    public String supportedLanguages; // comma separated list of languages
    public String gender;
    public String primarylanguage;

    public long createdAt;
    public VoiceItem() {
        this.createdAt = System.currentTimeMillis();

    }

 }
