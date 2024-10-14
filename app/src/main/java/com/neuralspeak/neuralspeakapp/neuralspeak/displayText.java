package com.neuralspeak.neuralspeakapp.neuralspeak;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.ScaleAnimation;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.MotionEvent;
import com.example.neuralspeak.R;

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
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display_text);

        String textToDisplay = getSharedPreferences("MyPrefs", MODE_PRIVATE).getString("SPEAK_TEXT", "");
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



        gestureDetector = new GestureDetector(this, new GestureListener());
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {


                // firstly we will get the scale factor
                scaleFactor *= (1 + (detector.getScaleFactor() - 1) * dampingFactor); // Apply damping factor

                float newTextSizeSp = hugeTextView.getTextSize() / getResources().getDisplayMetrics().scaledDensity * scaleFactor; // Convert to sp
                newTextSizeSp = Math.round(newTextSizeSp / stepSizeSp) * stepSizeSp; // Round to nearest step size
                newTextSizeSp = Math.max(24f, Math.min(newTextSizeSp, 200f)); // Limit font size in sp

                hugeTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, newTextSizeSp); // Set text size in sp
                hugeTextView.requestLayout(); // Request a layout pass to recalculate the text size
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
