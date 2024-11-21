package com.hoejmoseit.wingman.wingmanapp.backgroundtask;

import static com.hoejmoseit.wingman.wingmanapp.backgroundtask.LanguageSelector.getSelectedLanguage;

import android.app.Activity;
import android.content.Context;
import android.media.MediaPlayer;
import android.widget.Toast;

import com.hoejmoseit.wingman.R;
import com.hoejmoseit.wingman.wingmanapp.MainActivity;
import com.hoejmoseit.wingman.wingmanapp.database.SaidTextDao;
import com.hoejmoseit.wingman.wingmanapp.database.SaidTextItem;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayText {
	private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
	public static void playText(Context context, String speakText, SaidTextDao saidTextDao, String path, String speechSubscriptionKey, String serviceRegion, String selectedVoice, float pitch, float speed, boolean noVoice, SpeechConfig speechConfig, int languageToggle) {
		System.out.println(speakText);

		if (speakText.isEmpty()) {
			System.out.println("DER ER INTET ");
			Toast.makeText(context, R.string.ingen_text_til_at_l_se_op, Toast.LENGTH_SHORT).show();
			return;
		} else if (!ConnectionCheck.isInternetAvailable(context)) {
			Toast.makeText(context, R.string.du_har_ikke_internet_afspil_en_af_dine_gemte_s_tninger_eller_pr_v_igen_senere, Toast.LENGTH_SHORT).show();
			return;

		} else if (noVoice) {
			Toast.makeText(context, R.string.noVoiceSelected, Toast.LENGTH_SHORT).show();
			return;

		}

		try {


			// Note: this will block the UI thread, so eventually, you want to register for the event

			String ssml = MainActivity.getSsml(speakText, getSelectedLanguage(languageToggle), selectedVoice, pitch, speed);
			System.out.println(ssml);

			String selectedLanguage = MainActivity.getLanguageShortname(getSelectedLanguage(languageToggle)).toString();
			System.out.println(selectedLanguage);

			speechExecutor.execute(() -> {
				SaidTextItem saidTextItem = saidTextDao.getByText(speakText.trim());


				if(saidTextItem != null &&

						Objects.equals(selectedVoice, saidTextItem.voiceName) &&
						saidTextItem.pitch == pitch &&
						saidTextItem.audioFilePath != null &&
						saidTextItem.speed == speed &&
						saidTextItem.language.equals(selectedLanguage)
				) {
					System.out.println(saidTextItem.voiceName + selectedVoice);
					System.out.println(saidTextItem.pitch + pitch);
					System.out.println(saidTextItem.speed + speed);
					System.out.println(saidTextItem.language + selectedLanguage);
					System.out.println(saidTextItem.audioFilePath);

					// Get the audio file from saidTextItem and play it
					MediaPlayer player = new MediaPlayer();
					try {
						player.setDataSource(saidTextItem.audioFilePath);
						player.prepare();
					} catch (Exception e) {
						((Activity) context).runOnUiThread(() -> Toast.makeText(context, R.string.check_connection, Toast.LENGTH_SHORT).show());
						saidTextDao.deleteHistorik(saidTextItem);
						return;

					}
					player.start();


					return;
				}

				saidTextItem = new SaidTextItem();
				saidTextItem.saidText = speakText;
				saidTextItem.date = new Date();
				saidTextItem.voiceName = selectedVoice;
				saidTextItem.pitch = pitch;
				saidTextItem.speed = speed;
				saidTextItem.language = selectedLanguage;

				Long whatever = saidTextDao.insertHistorik(saidTextItem);
				saidTextItem = saidTextDao.getByText(speakText);


				// Initialize speech synthesizer and its dependencies
				assert (speechConfig != null);
				AudioConfig audioConfig = AudioConfig.fromWavFileOutput(path + "/" + saidTextItem.id + ".wav");
				SpeechSynthesizer synthesizer = new SpeechSynthesizer(speechConfig, audioConfig);

				assert (synthesizer != null);

				if (BluetoothSpeakerSoundChecker.isBluetoothSpeakerActive(context)) {

					BluetoothSpeakerSoundChecker.playSilentSound(); // Play silent sound for 1 second


				}
				try {
					SpeechSynthesisResult result = synthesizer.SpeakSsmlAsync(ssml).get();
					byte[] data = result.getAudioData();


					// Use the SSML string for saidText-to-speech
					if ((result == null)) {
						((Activity) context).runOnUiThread(() -> {
							Toast.makeText(context, R.string.check_connection, Toast.LENGTH_SHORT).show();
						});

						saidTextDao.deleteHistorik(saidTextItem);
						return;
					}


					if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
						{

							result.close();
						}
					}
					saidTextItem.audioFilePath = path + "/" + saidTextItem.id + ".wav";
					System.out.println("File path: " + saidTextItem.audioFilePath);
					saidTextItem.voiceName = selectedVoice;


					saidTextDao.updateHistorik(saidTextItem);
					MediaPlayer player = new MediaPlayer();
					player.setDataSource(saidTextItem.audioFilePath);
					System.out.println(saidTextItem.audioFilePath + " med " + selectedVoice);
					player.prepare();
					player.start();


				} catch (Exception e) {
					((Activity) context).runOnUiThread(() -> {
						Toast.makeText(context, R.string.check_connection, Toast.LENGTH_SHORT).show();
					});

				}

			});


		} catch (IllegalArgumentException ex) {
			Toast.makeText(context, R.string.check_info, Toast.LENGTH_SHORT).show();


		} catch (Exception e) {
			Toast.makeText(context, R.string.check_info, Toast.LENGTH_SHORT).show();

			e.printStackTrace();
			System.out.println(e.getMessage());
			System.out.println(e.getStackTrace());

		}
	}
}

