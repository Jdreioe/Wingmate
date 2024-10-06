package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(foreignKeys = @ForeignKey(entity = SpeechItem.class,
        parentColumns = "id",
        childColumns = "folderId",
        onDelete = ForeignKey.CASCADE),
        indices = {@Index("folderId")})
public class SpeechItem {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String name;

    public String text;

    public boolean isFolder;

    public Long folderId; // To distinguish folders from items
}
