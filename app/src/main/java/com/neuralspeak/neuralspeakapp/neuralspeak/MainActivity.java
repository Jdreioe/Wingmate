//
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE.md file in the project root for full license information.
//
// <code>
package com.neuralspeak.neuralspeakapp.neuralspeak;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;

import com.elvishew.xlog.BuildConfig;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.example.neuralspeak.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import static android.Manifest.permission.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();

    private SpeechConfig speechConfig;
    private SpeechSynthesizer synthesizer;
    private MaterialButtonToggleGroup languageToggle;
    private SharedPreferences sharedPreferences;
    private String speechSubscriptionKey;
    private String serviceRegion;
    private String selectedVoice = "BrianMultiLingual";
    private float pitch;
    private float speed;
    private SpeechItem deletedItem = null;
    private int deletedIndex = -1;
    private SpeechItemDao speechItemDao;
    private SpeechItemAdapter speechItemAdapter;
    private List<SpeechItem> speechItemsInCurrentFolder;

    // Keeps track of folder selection
    private int currentFolderId = -1;
    private boolean isSomeFolderSelected = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);
        topAppBar.setNavigationOnClickListener(v -> {
            if (isSomeFolderSelected) {
                databaseExecutor.execute(() -> {
                    SpeechItem currentFolder = speechItemDao.getItemById(currentFolderId);
                    if (currentFolder.parentId != null) {
                        selectFolder(currentFolder.parentId);
                    } else {
                        selectRootFolder();
                    }
                });
            }


        });

        Printer consolePrinter = new ConsolePrinter();
        Printer androidPrinter = new AndroidPrinter();
        LogConfiguration config = new LogConfiguration.Builder()
                .logLevel(BuildConfig.DEBUG ? LogLevel.ALL             // Specify log level, logs below this level won't be printed, default: LogLevel.ALL
                        : LogLevel.NONE)
                .tag("X-LOG")                                         // Specify TAG, default: "X-LOG"
                .enableThreadInfo()                                    // Enable thread info, disabled by default
                .enableStackTrace(2)                                   // Enable stack trace info with depth 2, disabled by default
                .enableBorder()
                .build();
        XLog.init(config, consolePrinter, androidPrinter);

        FirstTimeLaunchDialog.showFirstTimeLaunchDialog(this);

        XLog.d("Starting");


        databaseExecutor.execute(() -> {
            AppDatabase db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "speech_database")
                    .fallbackToDestructiveMigration() // Allow destructive migrations
                    .build();
            speechItemDao = db.speechItemDao();

            speechItemsInCurrentFolder = speechItemDao.getAllRootItems();

            runOnUiThread(() -> {

                RecyclerView recyclerView = findViewById(R.id.speech_items_list);
                recyclerView.setLayoutManager(new LinearLayoutManager(this));
                speechItemAdapter = new SpeechItemAdapter(speechItemsInCurrentFolder, speechItem -> {

                    if (speechItem.isFolder) {
                        // Handle folder click
                        selectFolder(speechItem.id);
                    } else {
                        // Handle item click
                        playText(speechItem.text);
                    }
                });
                recyclerView.setAdapter(speechItemAdapter);


                speechItemAdapter.notifyDataSetChanged();


                ItemTouchHelper.SimpleCallback simpleCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                    private Drawable icon;
                    private final ColorDrawable background = new ColorDrawable(Color.RED);

                    @Override
                    public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                        deletedIndex = viewHolder.getAdapterPosition();
                        deletedItem = speechItemsInCurrentFolder.get(deletedIndex);
                        speechItemsInCurrentFolder.remove(deletedIndex);
                        speechItemAdapter.notifyItemRemoved(deletedIndex);
                        deleteItem(deletedItem);
                        // showUndoSnackbar();
                    }

                    @Override
                    public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);

                        View itemView = viewHolder.itemView;
                        int backgroundCornerOffset = 20;

                        icon = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_delete); // Replace with your delete icon
                        int iconMargin = (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                        int iconTop = itemView.getTop() + (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                        int iconBottom = iconTop + icon.getIntrinsicHeight();

                        if (dX < 0) { // Swiping to the left
                            int iconLeft = itemView.getRight() - iconMargin - icon.getIntrinsicWidth();
                            int iconRight = itemView.getRight() - iconMargin;
                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);

                            background.setBounds(itemView.getRight() + ((int) dX) - backgroundCornerOffset,
                                    itemView.getTop(), itemView.getRight(), itemView.getBottom());
                        } else { // view is unswiped
                            background.setBounds(0, 0, 0, 0);
                        }

                        background.draw(c);
                        icon.draw(c);
                    }
                };

                ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleCallback);
                itemTouchHelper.attachToRecyclerView(recyclerView);


                    });




        });


        sharedPreferences = getSharedPreferences("MyPrefs", android.content.Context.MODE_PRIVATE);
        speechSubscriptionKey =  sharedPreferences.getString("sub_key", "");;
        serviceRegion = sharedPreferences.getString("sub_locale", "");

        languageToggle = this.findViewById(R.id.language_toggle);

        // Note: we need to request the permissions
        int requestCode = 5; // unique code for the permission request
        ActivityCompat.requestPermissions(MainActivity.this, new String[]{INTERNET}, requestCode);

        // Initialize speech synthesizer and its dependencies
        speechConfig = SpeechConfig.fromSubscription(speechSubscriptionKey, serviceRegion);
        assert(speechConfig != null);

        synthesizer = new SpeechSynthesizer(speechConfig);
        assert(synthesizer != null);


    }

    @SuppressLint("NotifyDataSetChanged")
    private void selectFolder(int folderId) {

        isSomeFolderSelected = true;
        currentFolderId = folderId;

        //fjern fra recycler
        speechItemsInCurrentFolder.clear();

        // find alle speechItems fra mappen og sæt dem ind i  speechItemsInCurrentFolder listen

        databaseExecutor.execute(() -> {
            speechItemsInCurrentFolder.addAll(speechItemDao.getAllItemsInFolder(folderId));

            //refresh recycler
            runOnUiThread(this::updateSpeechItems);

            // tilbageknap hvis isSomeFolderSelected == true:
            // isSomeFolderSelected = false
        });

        // vis tilbageknap
    }

    @SuppressLint("NotifyDataSetChanged")
    private void selectRootFolder() {
        isSomeFolderSelected = false;
        currentFolderId = -1;
        speechItemsInCurrentFolder.clear();

        databaseExecutor.execute(() -> {
            speechItemsInCurrentFolder.addAll(speechItemDao.getAllRootItems());
            runOnUiThread(this::updateSpeechItems);
        });
    }

    private void deleteItem(SpeechItem deletedItem) {
        databaseExecutor.execute(() -> {
            speechItemDao.deleteItems(List.of(deletedItem));
            speechItemsInCurrentFolder.remove(deletedItem);
            updateSpeechItems();
        });
    }

    private void insertItem(SpeechItem item) {
        databaseExecutor.execute(() -> {

            long ok = speechItemDao.insertItem(item);
            speechItemsInCurrentFolder.clear();
            if (isSomeFolderSelected) {
                speechItemsInCurrentFolder.addAll(speechItemDao.getAllItemsInFolder(currentFolderId));
            } else {
                speechItemsInCurrentFolder.addAll(speechItemDao.getAllRootItems());
            }
            updateSpeechItems();
        });
    }

    private static @NonNull String getSsml(String text, Language language, String Voice, float pitch, float speed) throws Exception {

        if (language == Language.MULTI  ) {
            return "<speak version='1.0' xml:lang='da-DK' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='http://www.w3.org/2001/mstts'>"
                    .concat(String.format("<voice name='%s'>", Voice))
                    .concat("<prosody rate='" + speed + "' pitch='" + pitch + "%'>" )
                    .concat(text)
                    .concat("</prosody>")
                    .concat("</voice>")
                    .concat("</speak>");
        } else {
            return "<speak version='1.0' xml:lang='da-DK' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='http://www.w3.org/2001/mstts'>"
                    .concat(String.format("<voice name='%s'>", Voice))
                    .concat("<prosody rate='" + speed + "' pitch='" + pitch + "%'>" )
                    .concat("<lang xml='" +  getLanguageShortname(language) + "'>")

                    .concat(text)
                    .concat("</lang>")
                    .concat("</prosody>")
                    .concat("</voice>")
                    .concat("</speak>");
        }

    }
    private static @NonNull String getLanguageShortname(Language language) throws Exception {
        switch (language) {
            case DANISH: return "da-DK";
            case ENGLISH: return "en-US";

        }
        throw new IllegalArgumentException("Invalid language");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Release speech synthesizer and its dependencies
        synthesizer.close();
        speechConfig.close();
    }
public void omDeleteButtonClicked(View v) {
        EditText speakText = this.findViewById(R.id.speak_text);
        speakText.setText("");
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    String text1 = sharedPreferences.getString("text1", "");
    String text2 = sharedPreferences.getString("text2", "");

}
public void onSettingsButtonClicked(View v) {
    Intent intent = new Intent(this, SettingsActivity.class);
    startActivity(intent);
}

public void onNewSpeechItemButtonClicked(View view) {

    new MaterialAlertDialogBuilder(this)
            .setTitle("Tilføj ny")
            .setView(R.layout.added_speech) // Inflate your custom layout
            .setPositiveButton("Save", (dialog, which) -> {
                // Handle save action

                EditText titleInput = ((AlertDialog) dialog).findViewById(R.id.title_input);
                EditText textInput = ((AlertDialog) dialog).findViewById(R.id.text_input);
                SwitchMaterial folderToggle = ((AlertDialog) dialog).findViewById(R.id.folder_toggle);

                String title = titleInput.getText().toString();
                String text = textInput.getText().toString();
                boolean isFolder = folderToggle.isChecked();

                SpeechItem speechItem = new SpeechItem();
                speechItem.text = text;
                speechItem.name = title;
                speechItem.isFolder = isFolder;

                if (isSomeFolderSelected) {
                    speechItem.parentId = currentFolderId;
                }
                System.out.println("currentFolderId: " + currentFolderId);

                // hvis man trykker på currentfolder, så skal den gemmes i currentfolder

                insertItem(speechItem);

                // Update the SpeechItem object with the new values
                // ...
            })
            .setNegativeButton("Cancel", (dialog, which) -> {
                // Handle cancel action (e.g., dismiss the dialog)
            })
            .show();
}



public void onSpeechButtonClicked(View v) {
        EditText speakText = this.findViewById(R.id.speak_text);
        playText(speakText.getText().toString());



}

    private void playText(String speakText) {
        try {
            // Note: this will block the UI thread, so eventually, you want to register for the event
            selectedVoice = sharedPreferences.getString("voice", "en-US-BrianMultilingualNeural");
            pitch = sharedPreferences.getFloat("pitch", 1f);
            speed = sharedPreferences.getFloat("speed", 1f);




            String ssml = getSsml(speakText, getSelectedLanguage(languageToggle),selectedVoice, pitch, speed);


            speechExecutor.execute(() -> {
                SpeechSynthesisResult result = synthesizer.SpeakSsml(ssml);
                // Use the SSML string for text-to-speech

                assert(result != null);

                if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                }
                result.close();
            });


        } catch (IllegalArgumentException ex) {
            Log.e("Illegal argument exception", "Måske noget med multisprog " + ex.getMessage());
            assert(false);
        }
        catch (Exception ex) {
            Log.e("SpeechSDKDemo", "unexpected " + ex.getMessage());
            assert(false);
        }
    }

    public Language getSelectedLanguage(MaterialButtonToggleGroup toggleGroup) {
        int checkedId = toggleGroup.getCheckedButtonId();
        if (checkedId == R.id.english_button){
            return Language.ENGLISH;}
        else if (checkedId == R.id.auto_button) {
            return Language.MULTI;
        }else if (checkedId == R.id.danish_button) {
                return Language.DANISH;
            }
        else{
            return Language.MULTI;
        }
    }

    private void updateSpeechItems() {
        databaseExecutor.execute(() -> {
            runOnUiThread(() -> speechItemAdapter.notifyDataSetChanged());                        }
        );
    }

    enum Language {
        DANISH,
        ENGLISH,
        MULTI;
    }
}

// </code>
