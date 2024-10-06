package com.neuralspeak.neuralspeakapp.neuralspeak;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import com.example.neuralspeak.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class FirstTimeLaunchDialog {

    public static void showFirstTimeLaunchDialog(Context context) {
        SharedPreferences sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE);
        boolean hasLaunchedBefore = sharedPrefs.getBoolean("has_launched_before", false);

        if (!hasLaunchedBefore) {
            MaterialAlertDialogBuilder materialDialogBuilder = new MaterialAlertDialogBuilder(context);
            LayoutInflater inflater = LayoutInflater.from(context);
            View dialogView = inflater.inflate(R.layout.first_time_launch, null);
            final EditText subkeyInput = dialogView.findViewById(R.id.subkey_input);
            final EditText subLocalInput = dialogView.findViewById(R.id.sub_local_input);

            materialDialogBuilder.setView(dialogView)
                    .setPositiveButton("Save", (dialog, which) -> {
                        String subkey = subkeyInput.getText().toString();
                        String subLocal = subLocalInput.getText().toString();

                        SharedPreferences.Editor editor = sharedPrefs.edit();
                        editor.putString("sub_key", subkey);
                        editor.putString("sub_locale", subLocal);
                        editor.putBoolean("has_launched_before", true);
                        editor.commit(); // Use apply() instead of commit()
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                    .show();
        }
    }
}