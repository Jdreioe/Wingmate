package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class Voice {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String name;

}
