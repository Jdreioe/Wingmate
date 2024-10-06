package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity
public class VoiceItem {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String name;

}
