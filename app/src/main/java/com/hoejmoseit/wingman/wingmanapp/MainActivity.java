//
// Copyright (c) Jonas Højmose Dreiøe. All rights reserved.
// Licensed under the GPL 3.0 license. See LICENSE.md file in the project root for full license information.

// <code>
package com.hoejmoseit.wingman.wingmanapp;

import static android.Manifest.permission.INTERNET;

import static com.hoejmoseit.wingman.wingmanapp.backgroundtask.PlayText.playText;
import static com.hoejmoseit.wingman.wingmanapp.database.AppDatabase.MIGRATION_9_10;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;

import com.hoejmoseit.wingman.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.hoejmoseit.wingman.wingmanapp.database.AppDatabase;
import com.hoejmoseit.wingman.wingmanapp.database.LanguageAdapter;
import com.hoejmoseit.wingman.wingmanapp.database.SaidTextDao;
import com.hoejmoseit.wingman.wingmanapp.database.SaidTextItem;
import com.hoejmoseit.wingman.wingmanapp.database.SpeechItem;
import com.hoejmoseit.wingman.wingmanapp.database.SpeechItemAdapter;
import com.hoejmoseit.wingman.wingmanapp.database.SpeechItemDao;
import com.hoejmoseit.wingman.wingmanapp.database.VoiceDao;
import com.hoejmoseit.wingman.wingmanapp.database.VoiceItem;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences sharedPreferences;

    private String selectedVoice;

    private SpeechItem deletedItem = null;
    private int deletedIndex = -1;
    private SpeechItemDao speechItemDao;
    private SaidTextDao saidTextDao;
    private SpeechItemAdapter speechItemAdapter;
    private List<SpeechItem> speechItemsInCurrentFolder;
    private final Object lock = new Object();

    private MaterialToolbar topAppBar;
    private MaterialButtonToggleGroup languageToggle;
    // Keeps track of folder selection
    private int currentFolderId = -1;
    private boolean isSomeFolderSelected = false;
    private long expirationTime = System.currentTimeMillis() - (1000*60*60*24*7);

    private EditText speakText;
    private String dynamicPath;
    private String currentSupportedLanguages;
    private String speechSubscriptionKey;
    private String serviceRegion;
    private float pitch;
    private float speed;
    private boolean noVoice;
    private SpeechConfig speechConfig;
    private AudioConfig audioConfig;
    private RecyclerView speechItemRecyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API level 34
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

            WindowInsetsController insetsController = getWindow().getInsetsController();
            View rootView = findViewById(android.R.id.content);
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (view, windowInsets) -> {
                Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                Insets systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                Insets displayCutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());


                view.setPadding(
                        systemBarsInsets.left,
                        systemBarsInsets.top,
                        systemBarsInsets.right,
                        imeInsets.bottom
                );
                        if (displayCutoutInsets.left > 0 || displayCutoutInsets.top > 0 ||
                                displayCutoutInsets.right > 0 || displayCutoutInsets.bottom > 0) {
                            view.setPadding(
                                    displayCutoutInsets.left,
                                    systemBarsInsets.top,
                                    displayCutoutInsets.right,
                                    imeInsets.bottom
                            );
                        }

                if (insetsController != null) {
                    insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }

                // Handle WindowInsets to adjust layout

                return windowInsets.CONSUMED;
            });

            if (insetsController != null) {
                insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }

            // Handle WindowInsets to adjust layout

        }


        speakText = this.findViewById(R.id.speak_text);
        topAppBar = findViewById(R.id.topAppBar);
        topAppBar.setNavigationIcon(R.mipmap.ic_launcher_foreground);
        topAppBar.setNavigationOnClickListener(v -> {




            // Hvis jeg er i en mappe,
            if (isSomeFolderSelected) {


                databaseExecutor.execute(() -> {


                    // If currentFolderId == -1 we know that Historik was selected
                    boolean wasHistorikSelected = currentFolderId == -1;
                    if (wasHistorikSelected) {
                        selectRootFolder();

                        return;
                    }
                    SpeechItem currentFolder = speechItemDao.getItemById(currentFolderId);

                    if (currentFolder.parentId != null ) {
                        selectFolder(currentFolder.parentId, currentFolder.name);
                    } else {
                        selectRootFolder();
                    }
                });
            } else {

                topAppBar.setNavigationIcon(R.mipmap.ic_launcher_foreground);

                databaseExecutor.execute(this::selectRootFolder);


            }



        });

        if (noVoice) {
            findViewById(R.id.language_toggle).setEnabled(false);
            findViewById(R.id.fullscreenButton).setEnabled(false);;
            findViewById(R.id.change_Language_Button).setEnabled(false);
            findViewById(R.id.speakButton).setEnabled(false);
            findViewById(R.id.addButton).setEnabled(false);
            findViewById(R.id.deleteButton).setEnabled(false);
            findViewById(R.id.speak_text).setEnabled(false);
            speakText.setText(R.string.noVoiceSelected);

        } else{
            findViewById(R.id.language_toggle).setEnabled(true);
            findViewById(R.id.fullscreenButton).setEnabled(true);;
            findViewById(R.id.change_Language_Button).setEnabled(true);
            findViewById(R.id.speakButton).setEnabled(true);
            findViewById(R.id.addButton).setEnabled(true);
            findViewById(R.id.deleteButton).setEnabled(true);
            findViewById(R.id.speak_text).setEnabled(true);
            speakText.setText("");

        }
        FirstTimeLaunchDialog.showFirstTimeLaunchDialog(this);





            // sets saidTextItems to id 0 as a folder  ;


        databaseExecutor.execute(() -> {
            sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE);
            selectedVoice = sharedPreferences.getString("voice", "");
            speechSubscriptionKey = sharedPreferences.getString("sub_key", "");
            serviceRegion = sharedPreferences.getString("sub_locale", "");
            pitch = sharedPreferences.getFloat("pitch", 1f);
            speed = sharedPreferences.getFloat("speed", 1f);
            noVoice = sharedPreferences.getBoolean("noVoice", true);
            speechConfig = SpeechConfig.fromSubscription(speechSubscriptionKey, serviceRegion);
            dynamicPath = getFilesDir().getAbsolutePath();

            AppDatabase db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "speech_database")
                    .addMigrations(MIGRATION_9_10) // Allow destructive migrations
                    .build();
            speechItemDao = db.speechItemDao();
            saidTextDao = db.saidTextDao();

            speechItemsInCurrentFolder = speechItemDao.getAllRootItems();

            runOnUiThread(() -> {

                speechItemRecyclerView = findViewById(R.id.speech_items_list);
                speechItemRecyclerView.setLayoutManager(new LinearLayoutManager(this));




                speechItemAdapter = new SpeechItemAdapter(speechItemsInCurrentFolder, speechItem -> {





                    if (speechItem.isFolder) {
                        // Handle folder click
                        databaseExecutor.execute(() -> selectFolder(speechItem.id, speechItem.name));
                    } else {
                        // Handle item click

                        System.out.println(selectedVoice);

                        playText(this, speechItem.text, saidTextDao, dynamicPath, speechSubscriptionKey, serviceRegion, selectedVoice, pitch, speed, noVoice, speechConfig, languageToggle.getCheckedButtonId());
                    }
                });
                speechItemRecyclerView.setAdapter(speechItemAdapter);


                speechItemAdapter.notifyDataSetChanged();
                updateSpeechItems();



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
                itemTouchHelper.attachToRecyclerView(speechItemRecyclerView);


            });


        });


        languageToggle = this.findViewById(R.id.language_toggle);

        // Note: we need to request the permissions
        int requestCode = 5; // unique code for the permission request
        ActivityCompat.requestPermissions(this, new String[]{INTERNET}, requestCode);



    }

    @SuppressLint("NotifyDataSetChanged")
    private void selectFolder(int folderId, String folderName) {
        runOnUiThread(() -> {
            topAppBar.setTitle(folderName);
            topAppBar.setNavigationIcon(R.drawable.ic_back);
            // topAppBar.setNavigationIconTint(getDynamicColor(android.R.attr.colorPrimary));
        });
        currentFolderId = folderId;
        //fjern fra recycler
       runOnUiThread(()-> speechItemsInCurrentFolder.clear());
       isSomeFolderSelected = true;
        if (folderId == -1) {
            onHistorikSelected();
            return;
        }

        // find alle speechItems fra mappen og sæt dem ind i  speechItemsInCurrentFolder listen

        databaseExecutor.execute(() -> {
            List<SpeechItem> currentItems = speechItemDao.getAllItemsInFolder(folderId);
            runOnUiThread(()-> speechItemsInCurrentFolder.addAll(currentItems));

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
        List<SpeechItem> rootItems = speechItemDao.getAllRootItems();;

        synchronized (lock) { // Synchronize on the list itself
            runOnUiThread(() -> speechItemsInCurrentFolder.clear());


        }
        synchronized (lock) { // Synchronize on the list itself
            runOnUiThread(() -> {
                speechItemsInCurrentFolder.addAll(rootItems);


                updateSpeechItems();
                topAppBar.setNavigationIcon(R.mipmap.ic_launcher_foreground);
                topAppBar.setTitle("Wingman");
            });



        }

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

    private int getDynamicColor(int colorAttribute) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(colorAttribute, typedValue, true);
        return typedValue.data;
    }

    public static @NonNull String getSsml(String text, Language language, String Voice, float pitch, float speed) throws Exception {


        String startSSML = "<speak version='1.0' xml:lang='da-DK' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='http://www.w3.org/2001/mstts'>"
                .concat(String.format("<voice name='%s'>", Voice))
                .concat("<prosody rate='" + speed + "' pitch='" + pitch + "%'>");
        if (language == Language.MULTI) {
            return startSSML
                    .concat(text)
                    .concat("</prosody>")
                    .concat("</voice>")
                    .concat("</speak>");
        } else {
            return startSSML
                    .concat("<lang xml='" + getLanguageShortname(language) + "'>")
                    .concat(text)
                    .concat("</lang>")
                    .concat("</prosody>")
                    .concat("</voice>")
                    .concat("</speak>");
        }

    }

    public static @NonNull String getLanguageShortname(Language language) throws Exception {
        switch (language) {
            case DANISH:


                return "da-DK";
            case ENGLISH:
                return "en-US";
            case MULTI:
                return "multi";

        }

        throw new IllegalArgumentException("Invalid language");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Release speech synthesizer and its dependencies
//        synthesizer.close();
//        speechConfig.close();
    }

    public void omDeleteButtonClicked(View v) {
        speakText.setText("");
        //String text1 = sharedPreferences.getString("text1", "");
        //String text2 = sharedPreferences.getString("text2", "");

    }

    public void onSettingsButtonClicked(View v) {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    public void onNewSpeechItemButtonClicked(View view) {

        AlertDialog alertDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_speech_item_title)
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
        ((EditText)alertDialog.findViewById(R.id.text_input))
                .setText(speakText.getText().toString());

    }


    public void onSpeechButtonClicked(View v) {

        EditText speakText = this.findViewById(R.id.speak_text);
        
        String s = speakText.getText().toString().trim();
        System.out.println(s);
        if (s.isEmpty()) {
            return;
        }
        sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE);
        selectedVoice = sharedPreferences.getString("voice", "");
        pitch = sharedPreferences.getFloat("pitch", 1f);
        speed = sharedPreferences.getFloat("speed", 1f);

        playText(this, s,
                saidTextDao,
                dynamicPath,
                speechSubscriptionKey,
                serviceRegion,
                selectedVoice,
                pitch,
                speed,
                noVoice,
                speechConfig,
                languageToggle.getCheckedButtonId());


    }




    private void updateSpeechItems() {
        if (!isSomeFolderSelected) {
            SpeechItem historik = new SpeechItem();
            historik.name = getString(R.string.historyitem);
            historik.id = -1;
            historik.isFolder = true;
            historik.parentId = -1;


            speechItemsInCurrentFolder.add(0, historik);
        }
        runOnUiThread(() -> speechItemAdapter.notifyDataSetChanged());

    }
    public void onFullscreenButronClicked(View v) {
        Intent intent = new Intent(this, displayText.class);
        sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE);
        EditText speakText = this.findViewById(R.id.speak_text);

        SharedPreferences.Editor editor = sharedPreferences.edit();
        System.out.println(languageToggle.getCheckedButtonId());
        editor.putInt("languageToggle", languageToggle.getCheckedButtonId());

        System.out.println(speakText.getText().toString());
        editor.putString("SPEAK_TEXT", speakText.getText().toString());
        editor.apply();
        startActivity(intent);
    }

    public void onHistorikSelected(){

        List<SaidTextItem> historik = saidTextDao.getAll();
        for (SaidTextItem item : historik) {
            SpeechItem speechItem = new SpeechItem();

            speechItem.text = item.saidText;;
            speechItem.id = item.id;
            speechItem.isFolder = false;
            speechItemsInCurrentFolder.add(speechItem);

        }
        if (((RecyclerView) findViewById(R.id.speech_items_list)).isComputingLayout())
        {
            findViewById(R.id.speech_items_list).post(new Runnable()
            {
                @Override
                public void run() {
                    speechItemAdapter.notifyDataSetChanged();
                }
            });
        } else {
            runOnUiThread(() -> {
                speechItemAdapter.notifyDataSetChanged();

            });
        }


    }
    public enum Language {
        DANISH,
        ENGLISH,
        MULTI
    }

    public void onChangeLanguageButtonClicked(View view) {
        final List<String>[] languageList = new List[1];
        databaseExecutor.execute(() -> {

                    AppDatabase db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "speech_database")
                            .build();
                    VoiceDao voiceDao = db.voiceDao();
                    sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE);
                    selectedVoice = sharedPreferences.getString("voice", "");

                    List<VoiceItem> voices = voiceDao.getAllVoices(expirationTime);
                    for (VoiceItem voice : voices) {

                        if (Objects.equals(voice.name, selectedVoice)) {
                            currentSupportedLanguages = voice.supportedLanguages;
                            String[] languages = currentSupportedLanguages.split(",");
                            languageList[0] = Arrays.asList(languages);
                            runOnUiThread(() -> {
                                MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                                        .setTitle("Select language")
                                        .setView(R.layout.language_selector)
                                        .setPositiveButton("OK", (dialog1, which) -> {
                                            // Handle OK button click
                                            RecyclerView recyclerView = findViewById(R.id.languageRecyclerView);
                                            recyclerView.setLayoutManager(new LinearLayoutManager(this));
                                            LanguageAdapter languageAdapter = new LanguageAdapter(languageList[0]);
                                            recyclerView.setAdapter(languageAdapter);
                                            recyclerView.getAdapter().notifyDataSetChanged();

                                            for (String item :languageList[0]) {
                                                languageAdapter.addItem(item);
                                            }
                                        })
                                        .setNegativeButton("Cancel", (dialog12, which) -> {
                                            // Handle Cancel button click

                                        });
                                dialog.show();
                            });

                        }
                    }
                });






//        List<String> listOfSupportedLanguages = currentSupportedLanguages.split(",".map;
    }

}