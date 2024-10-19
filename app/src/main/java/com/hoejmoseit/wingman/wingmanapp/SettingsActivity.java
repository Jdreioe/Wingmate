package com.hoejmoseit.wingman.wingmanapp;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;

import com.hoejmoseit.wingman.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.hoejmoseit.wingman.wingmanapp.database.AppDatabase;
import com.hoejmoseit.wingman.wingmanapp.database.VoiceDao;
import com.hoejmoseit.wingman.wingmanapp.database.VoiceItem;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import androidx.room.Room;

public class SettingsActivity extends AppCompatActivity {
    private List<String> voices = new ArrayList<>();

    private EditText subscriptionID;
    private EditText resourceLocale;
    private SharedPreferences sharedPreferences;

    private static final ExecutorService restExecutor = Executors.newSingleThreadExecutor();
    private Spinner voiceSpinner;
    private String selectedVoice;
    private int selectedVoiceIndex;
    private SharedPreferences.Editor editor;
    private final String SECONDARY_LOCALE_LIST = "SecondaryLocaleList";
    private List<VoiceItem> downloadedVoiceItems;
    private boolean maleCheck;
    private boolean multiCheck;
    private boolean femaleCheck;
    private boolean neutralCheck;
    private AppDatabase db;
    private VoiceDao voiceDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.settings_activity);
        subscriptionID = this.findViewById(R.id.subscriptionKey);
        resourceLocale = this.findViewById(R.id.resourceLocale);
        Slider speedSlider = this.findViewById(R.id.speed_slider);
        Slider pitchSlider = this.findViewById(R.id.pitch_slider);
        MaterialButton selectVoiceButton = this.findViewById(R.id.selectVoiceButton);
        selectVoiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVoiceSelectionDialog();


            }
        });
        sharedPreferences = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE);


        // ... other listener methods ...
        speedSlider.addOnChangeListener((slider, value, fromUser) -> {
        editor = sharedPreferences.edit();
        editor.putFloat("speed", value);
        editor.commit();
        });

        pitchSlider.addOnChangeListener((slider, value, fromUser) -> {
            editor = sharedPreferences.edit();

            editor.putFloat("pitch", value);

            editor.commit();
        });

        String text1 = sharedPreferences.getString("sub_key", "");
        String text2 = sharedPreferences.getString("sub_locale", "");
        sharedPreferences.getString("voice", "");
        float speed = sharedPreferences.getFloat("speed", 1f);
        float pitch = sharedPreferences.getFloat("pitch", 1f);

        subscriptionID.setText(text1);
        resourceLocale.setText(text2);
        speedSlider.setValue(speed);
        pitchSlider.setValue(pitch);
    }

    private void showVoiceSelectionDialog() {
        // Create and show the voice selection dialog
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        dialog.setContentView(R.layout.voices); // Set content view
        // Initialize views in the dialog
        CheckBox maleCheckbox = dialog.findViewById(R.id.maleCheckBox);
        CheckBox femaleCheckbox = dialog.findViewById(R.id.femaleCheckBox);
        CheckBox neutralCheckbox = dialog.findViewById(R.id.neutralCheckBox);
        SwitchMaterial multilingualSwitch = dialog.findViewById(R.id.multilingualSwitch);

        restExecutor.execute(() -> {
            db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "speech_database").fallbackToDestructiveMigration().build();
            voiceDao = db.voiceDao();
            downloadedVoiceItems = voiceDao.getAllVoices();

            sharedPreferences = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE);
            multiCheck = sharedPreferences.getBoolean("isMultilingualChecked", true);
            maleCheck = sharedPreferences.getBoolean("isMaleChecked", false);
            femaleCheck = sharedPreferences.getBoolean("isFemaleChecked", false);
            neutralCheck = sharedPreferences.getBoolean("isNeutralChecked", false);

            runOnUiThread(() -> {
                // Set initial checked state
                maleCheckbox.setChecked(maleCheck);
                femaleCheckbox.setChecked(femaleCheck);
                neutralCheckbox.setChecked(neutralCheck);
                multilingualSwitch.setChecked(multiCheck);

                filterVoiceList(maleCheckbox, femaleCheckbox, neutralCheckbox, multilingualSwitch);
            });
        });

        maleCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateFilter(maleCheckbox, femaleCheckbox, neutralCheckbox, multilingualSwitch));
        femaleCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateFilter(maleCheckbox, femaleCheckbox, neutralCheckbox, multilingualSwitch));
        neutralCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateFilter(maleCheckbox, femaleCheckbox, neutralCheckbox, multilingualSwitch));
        multilingualSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateFilter(maleCheckbox, femaleCheckbox, neutralCheckbox, multilingualSwitch));

        voiceSpinner = dialog.findViewById(R.id.voice_spinner);
        restExecutor.execute(this::retrieveVoicesAndSetupVoiceSpinner);
        dialog.setTitle(R.string.select_voice);
        dialog.show();
        dialog.findViewById(R.id.saveVoices).setOnClickListener(v -> {
            dialog.dismiss();
        });


    }

    private void updateFilter(CheckBox maleCheckbox, CheckBox femaleCheckbox, CheckBox neutralCheckbox, SwitchMaterial multilingualSwitch) {
        filterVoiceList(maleCheckbox, femaleCheckbox, neutralCheckbox, multilingualSwitch);
        int position = voiceSpinner.getSelectedItemPosition();
        editor.putInt("selected_voice_index", position);
        editor.commit();
    }

    private void filterVoiceList(CheckBox maleCheckbox,
                                 CheckBox femaleCheckbox,
                                 CheckBox neutralCheckbox,
                                 SwitchMaterial multilingualSwitch) {
        boolean isMaleChecked = maleCheckbox.isChecked();
        boolean isFemaleChecked = femaleCheckbox.isChecked();
        boolean isNeutralChecked = neutralCheckbox.isChecked();
        boolean isMultilingualChecked = multilingualSwitch.isChecked();
        System.out.println(downloadedVoiceItems);
        if(downloadedVoiceItems.isEmpty()){
            return;
        };
        List<String> filteredVoices = downloadedVoiceItems.stream().filter(voiceItem -> {
            boolean isVoiceMultilingual = voiceItem.supportedLanguages.contains(",");
            if (!isMaleChecked && !isFemaleChecked && !isNeutralChecked) {
                return isMultilingualChecked ? isVoiceMultilingual : true;
            } else {
                boolean hasOneOfSelectedGenders = voiceItem.gender.equals("Male") && isMaleChecked ||
                        voiceItem.gender.equals("Female") && isFemaleChecked ||
                        voiceItem.gender.equals("Neutral") && isNeutralChecked;
                return hasOneOfSelectedGenders && (isMultilingualChecked ? isVoiceMultilingual : true);
            }
        }).map(voiceItem -> voiceItem.name).collect(Collectors.toList());

        editor = sharedPreferences.edit();
        editor.putBoolean("isMultilingualChecked", isMultilingualChecked);
        editor.putBoolean("isMaleChecked", isMaleChecked);
        editor.putBoolean("isFemaleChecked", isFemaleChecked);
        editor.putBoolean("isNeutralChecked", isNeutralChecked);
        editor.commit();

        voices.clear();
        voices.addAll(filteredVoices);


    }


    private void retrieveVoicesAndSetupVoiceSpinner() {

        // TODO: Update downloadedvoices
        if (voiceDao.getAllVoices().isEmpty()) {

            voices = getVoices(); // Your API call and parsing logic
        }
        else {

            for (VoiceItem voice : downloadedVoiceItems ) {
                voices.add(voice.name);

            }
       }
            runOnUiThread(() -> {
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, voices);
                voiceSpinner.setAdapter(adapter);
                voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                        selectedVoice = voices.get(position);
                        // Store selectedVoice in SharedPreferences or other storage
                        editor = sharedPreferences.edit();
                        editor.putString("voice", selectedVoice);
                        editor.putInt("selected_voice_index", position);
                        editor.commit();


                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // Handle case where nothing is selected
                    }
                });

            });
        selectedVoiceIndex = sharedPreferences.getInt("selected_voice_index", 0);

        runOnUiThread(() -> voiceSpinner.setSelection(selectedVoiceIndex));



    }


    public void onSaveButtonClicked(View v) {
        String azureSubscriptionKey = subscriptionID.getText().toString();
        String azureSubscriptionLocale = resourceLocale.getText().toString();
        // Save the values.
        // You can use SharedPreferences, a database, or any other suitable method.
        // Example using SharedPreferences:
        editor = sharedPreferences.edit();

        editor.putString("sub_key", azureSubscriptionKey);
        editor.putString("sub_locale", azureSubscriptionLocale);

        editor.commit();



        // Optionally, display a toast message or navigate back to the previous activity.
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();

        }


    /**
     * This method MUST be called from a background thread
     *
     * @return the voices that are available
     */
    private List<String> getVoices() {
        // Ensure this method is called from a background thread

        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("This must be called from a background thread.");
        }




        String speechSubKey = subscriptionID.getText().toString().trim();
        String speechRegion = resourceLocale.getText().toString().trim();

        List<String> voices = new ArrayList<>();
        try {
            URL url = new URL("https://" + speechRegion + ".tts.speech.microsoft.com/cognitiveservices/voices/list");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", speechSubKey);
            connection.setRequestProperty("Content-Type", "ssml+xml");
            connection.setRequestProperty("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm");
            connection.setRequestProperty("User-Agent","Wingman 1.0");

            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                StringBuilder response = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Parse the JSON response and extract voice names
                JSONArray voicesArray = new JSONArray(response.toString());


                for (int i = 0; i < voicesArray.length(); i++) {

                    JSONObject voiceObject = voicesArray.getJSONObject(i);
                    String voiceName = voiceObject.getString("ShortName");
                    String gender = voiceObject.getString("Gender");
                    String primaryLanguage = voiceObject.getString("Locale");
                    voices.add(voiceName);
                    VoiceItem voiceItem = new VoiceItem();
                    voiceItem.name = voiceName;
                    voiceItem.gender = gender;
                    voiceItem.primarylanguage = primaryLanguage;
                    List<String> supportedLanguagesList = new ArrayList<>();


                    if (voiceObject.has(SECONDARY_LOCALE_LIST)) {
                        JSONArray supportedLanguagesJson = voiceObject.getJSONArray(SECONDARY_LOCALE_LIST);
                        for (int index = 0; index < supportedLanguagesJson.length(); index++) {
                            String language = (String) supportedLanguagesJson.get(index);
                            supportedLanguagesList.add(language);
                        }

                    }
                    System.out.println(supportedLanguagesList);
                    voiceItem.supportedLanguages = String.join("," ,supportedLanguagesList);

                    AppDatabase db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "speech_database").fallbackToDestructiveMigration().build();
                    VoiceDao voiceDao= db.voiceDao();

                    voiceDao.insert(voiceItem);

                }



            } else {


                Toast.makeText(this, "Fejl ved oprettelse af stemmer", Toast.LENGTH_SHORT).show();
                // Handle error response

            }
            connection.disconnect();
        } catch (Exception e) {
            // Handle exceptions (e.g., network errors, JSON parsing errors)
            System.out.println("Fejl ved oprettelse af stemmer " + e.getMessage());
            Toast.makeText(this, "Fejl ved oprettelse af stemmer", Toast.LENGTH_SHORT).show();



        }
        return voices;
    }

    }