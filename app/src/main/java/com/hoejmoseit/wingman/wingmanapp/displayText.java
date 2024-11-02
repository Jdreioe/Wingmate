package com.hoejmoseit.wingman.wingmanapp;

import android.content.res.ColorStateList;
import android.os.Bundle;

import com.hoejmoseit.wingman.wingmanapp.backgroundtask.PlayText;
import com.hoejmoseit.wingman.wingmanapp.database.*;
import com.google.android.material.imageview.ShapeableImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.room.Room;

import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.ScaleGestureDetector;
import android.widget.TextView;
import android.view.MotionEvent;
import com.hoejmoseit.wingman.R;
import com.microsoft.cognitiveservices.speech.SpeechConfig;

public class displayText extends AppCompatActivity {
    public ShapeableImageView backButton;
    public ShapeableImageView playTextButton;
    private float stepSizeSp = 8f;
    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector gestureDetector;
    private TextView hugeTextView;
    private ScaleGestureDetector scaleGestureDetector;
    private float dampingFactor = 0.3f; // Adjust this value to control sensitivity
    private float scaleFactor = 1;
    private AppDatabase db;
    private SaidTextDao saidTextDao;
    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display_text);

        String textToDisplay = getSharedPreferences("MyPrefs", MODE_PRIVATE).getString("SPEAK_TEXT", "");
        db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "speech_database")// Allow destructive migrations
                .build();
        saidTextDao = db.saidTextDao();
        path = getFilesDir().getAbsolutePath();
        boolean noVoice = getSharedPreferences("MyPrefs", MODE_PRIVATE).getBoolean("noVoice", false);
        String speechSubscriptionKey = getSharedPreferences("MyPrefs", MODE_PRIVATE).getString("sub_key", "");
        String serviceRegion = getSharedPreferences("MyPrefs", MODE_PRIVATE).getString("sub_locale", "");
        String selectedVoice = getSharedPreferences("MyPrefs", MODE_PRIVATE).getString("voice", "");
        float pitch = getSharedPreferences("MyPrefs", MODE_PRIVATE).getFloat("pitch", 1f);
        float speed = getSharedPreferences("MyPrefs", MODE_PRIVATE).getFloat("speed", 1f);
        int languageToggleId = getSharedPreferences("MyPrefs", MODE_PRIVATE).getInt("LanguageToggle", 0);
        SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechSubscriptionKey, serviceRegion);


        backButton = findViewById(R.id.backButton);
        playTextButton = findViewById(R.id.playTextButton);

        ColorStateList iconTint = ColorStateList.valueOf(getResources().getColor(R.color.colorTertiary, getTheme()));
        backButton.setImageTintList(iconTint);
        playTextButton.setImageTintList(iconTint);

        if (textToDisplay == null) {
            textToDisplay = "";
        }
        System.out.println(textToDisplay);
        hugeTextView = findViewById(R.id.hugeText);
        hugeTextView.setText(textToDisplay);


        ShapeableImageView backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        ShapeableImageView playTextButton = findViewById(R.id.playTextButton);

        playTextButton.setOnClickListener(v -> {
            String text = hugeTextView.getText().toString();


            PlayText.playText(this,
                    text,
                    saidTextDao,
                    path,
                    speechSubscriptionKey,
                    serviceRegion,
                    selectedVoice,
                    pitch,
                    speed,
                    noVoice,
                    speechConfig,
                    languageToggleId);
        });



        gestureDetector = new GestureDetector(this, new GestureListener());
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {


                // firstly we will get the scale factor
                scaleFactor *= (1 + (detector.getScaleFactor() - 1) * dampingFactor); // Apply damping factor

                float newTextSizeSp = hugeTextView.getTextSize() / getResources().getDisplayMetrics().scaledDensity * scaleFactor; // Convert to sp
                newTextSizeSp = Math.round(newTextSizeSp / stepSizeSp) * stepSizeSp; // Round to nearest step size
                newTextSizeSp = Math.max(24f, Math.min(newTextSizeSp, 200f)); // Limit font size in sp

                hugeTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, newTextSizeSp); // Set saidText size in sp
                hugeTextView.requestLayout(); // Request a layout pass to recalculate the saidText size
                hugeTextView.invalidate();

                return true;

            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        super.dispatchTouchEvent(event);

        // special types of touch screen events such as pinch ,
        // double tap, scrolls , long presses and flinch,
        // onTouch event is called if found any of these
        if (scaleGestureDetector != null) {
            scaleGestureDetector.onTouchEvent(event);
        }



//        mScaleGestureDetector.onTouchEvent(event);

//        gestureDetector.onTouchEvent(event);
        return gestureDetector.onTouchEvent(event);
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return true;
        }
        }

}
