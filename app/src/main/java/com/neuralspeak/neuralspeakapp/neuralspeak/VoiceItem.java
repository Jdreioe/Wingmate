package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

import java.util.List;

@Entity
public class VoiceItem {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String name;
    public String supportedLanguages; // comma separated list of languages
    public String gender;
    public String primarylanguage;
 }
