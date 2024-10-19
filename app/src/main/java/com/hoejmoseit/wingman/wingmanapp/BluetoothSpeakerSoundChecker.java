package com.hoejmoseit.wingman.wingmanapp;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public class BluetoothSpeakerSoundChecker {

    public static boolean isBluetoothSpeakerActive(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        for (AudioDeviceInfo device : devices) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                // Check if this Bluetooth device is the active output
                if (audioManager.isBluetoothA2dpOn()) {
                    // Use reflection to access getBluetoothA2dpDeviceId
                    try {
                        Method getBluetoothA2dpDeviceIdMethod = AudioManager.class.getMethod("getBluetoothA2dpDeviceId");
                        int bluetoothA2dpDeviceId = (int) getBluetoothA2dpDeviceIdMethod.invoke(audioManager);
                        if (device.getId() == bluetoothA2dpDeviceId) {
                            return true;
                        }
                    } catch (NoSuchMethodException | IllegalAccessException |
                             InvocationTargetException e) {
                        // Method not available, use fallback
                        // ... (Fallback logic, e.g., assume Bluetooth is active if isBluetoothA2dpOn is true)
                        return audioManager.isBluetoothA2dpOn(); // Fallback: Assume active if A2DP is on
                    }
                }
            }
        }

        return false;
    }
    public static void playSilentSound() {
        // Create an AudioTrack for silent playback
        int sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);

        // Create a silent buffer (all values set to 0)
        byte[] silentBuffer = new byte[bufferSize];
        Arrays.fill(silentBuffer, (byte) 0);

        // Play the silent sound for 1 second
        audioTrack.play();
        audioTrack.write(silentBuffer, 0, bufferSize);
        try {
            Thread.sleep(150); // Sleep for 150 ms to ensure the sound is played for 150 ms to make sure the entire sound from speechssmæl is played
            } catch (InterruptedException e) {
            e.printStackTrace();
        }
        audioTrack.stop();
        audioTrack.release();
    }
}