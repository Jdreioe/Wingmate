package com.hoejmoseit.wingman.wingmanapp.backgroundtask;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.hoejmoseit.wingman.R;
import com.hoejmoseit.wingman.wingmanapp.MainActivity;

public class LanguageSelector {
	public static MainActivity.Language getSelectedLanguage(int checkedId) {
		if (checkedId == R.id.english_button) {
			return MainActivity.Language.ENGLISH;
		} else if (checkedId == R.id.auto_button) {
			return MainActivity.Language.MULTI;
		} else if (checkedId == R.id.danish_button) {
			return MainActivity.Language.DANISH;
		} else {
			return MainActivity.Language.MULTI;
		}

	}}
