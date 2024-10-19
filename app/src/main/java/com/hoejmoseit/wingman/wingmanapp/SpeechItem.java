package com.hoejmoseit.wingman.wingmanapp;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(foreignKeys = @ForeignKey(entity = SpeechItem.class,
        parentColumns = "id",
        childColumns = "parentId",
        onDelete = ForeignKey.CASCADE),
        indices = {@Index("parentId")})
public class SpeechItem {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String name;

    public String text;

    public boolean isFolder;

    public Integer parentId;// To distinguish folders from items


}
