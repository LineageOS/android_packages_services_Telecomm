package com.android.server.telecom.testapps.localvoicemail;

/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.telecom.Call;
import android.telecom.LocalVoicemailService;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.server.telecom.testapps.localvoicemail.R;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A bare minimum proof of concept for a local voicemail service.  Plays an outgoing greeting and
 * then records the users's response and hangs up.
 */
public class TestLocalVoicemailService extends LocalVoicemailService {
    private final String TAG = "TestLocalVoicemailService";

    private WavAudioRecorder mWavAudioRecorder;

    @Override
    public void onVoicemailRequested(@NonNull Call.Details call) {
        Log.i(TAG, "onVoicemailRequested " + call.getId());
        try {
            AudioManager audioManager = getApplicationContext().getSystemService(
                    AudioManager.class);
            AudioFormat format = new AudioFormat.Builder().setSampleRate(16000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            AudioTrack uplinkInjectionTrack = audioManager.getCallUplinkInjectionAudioTrack(format);

            AudioFormat formatIn = new AudioFormat.Builder().setSampleRate(16000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            AudioRecord downlinkExtractionTrack = audioManager.getCallDownlinkExtractionAudioRecord(
                    formatIn);

            // Note: A well architected app would handle this in a more clean manner than a new
            // thread like this.
            Thread playbackThread = new Thread(() -> {
                AssetFileDescriptor afd = null;
                FileInputStream fis = null;
                try {
                    Log.i(TAG, "Attempting to play greeting on uplink track for call: "
                            + call.getId());

                    // Track needs to be in a playing state for us to write to it.
                    if (uplinkInjectionTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        uplinkInjectionTrack.play();
                    }

                    afd = getResources().openRawResourceFd(
                            R.raw.localvmgreeting); // Replace R with your_package_name.R if needed
                    if (afd == null) {
                        Log.e(TAG, "Failed to open greeting. Resource not found.");
                        // If the resource is essential and not found, stop voicemail processing.
                        disconnectCall();
                        return;
                    }
                    fis = afd.createInputStream();

                    // Use a buffer to read data in chunks. A common buffer size is 4KB.
                    // The optimal size might depend on the AudioTrack's configuration.
                    // 16000 * 1000 * 2 / 8
                    int bufferSize = 16000 * 1000 * 2 / 8;
                    byte[] buffer = new byte[bufferSize];
                    int bytesRead;

                    Log.i(TAG, "Starting to stream R.raw.greeting...");
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        // Write audio data to the uplink track.
                        // This is a blocking call.
                        int bytesWritten = uplinkInjectionTrack.write(buffer, 0, bytesRead);
                        if (bytesWritten < 0) {
                            Log.e(TAG, "Error writing to AudioTrack: " + bytesWritten
                                    + ". Stopping playback.");
                            // An error code from write() indicates a problem.
                            // See AudioTrack.write() documentation for error codes.
                            break;
                        }
                    }
                    Log.i(TAG, "Finished streaming R.raw.greeting.");

                } catch (IOException e) {
                    Log.e(TAG, "IOException during playback of greeting", e);
                } catch (IllegalStateException e) {
                    Log.e(TAG,
                            "IllegalStateException for AudioTrack (e.g., not initialized, "
                                    + "released, or play() failed)",
                            e);
                } catch (Exception e) { // Catch any other unexpected exceptions
                    Log.e(TAG, "Unexpected error during playback of greeting", e);
                } finally {
                    // Close resources
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (IOException e) {
                            Log.w(TAG, "Error closing FileInputStream", e);
                        }
                    }
                    if (afd != null) {
                        try {
                            afd.close();
                        } catch (IOException e) {
                            Log.w(TAG, "Error closing AssetFileDescriptor", e);
                        }
                    }

                    // Save the incoming message to a file with date/time stamp and incoming number.
                    String phoneNumber = call.getHandle() == null ? "unknown"
                            : call.getHandle().getSchemeSpecificPart();
                    String filePath = getApplicationContext().getFilesDir().getPath() + "/"
                            + getCurrentFormattedDateTime() + " - " + phoneNumber + ".wav";
                    mWavAudioRecorder = new WavAudioRecorder();
                    mWavAudioRecorder.startRecording(downlinkExtractionTrack, filePath);

                    // Note: A well architected app would not sleep the recording thread like this,
                    // but this is not a well architected app.
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } finally {
                        // And then we'll terminate the recording and hang up.
                        if (mWavAudioRecorder != null) {
                            mWavAudioRecorder.stopRecording();
                        }
                        disconnectCall();
                    }
                }
            });
            playbackThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to record!", e);
        }

    }

    /**
     * Returns the current date and time as a formatted string.
     * <p>
     * The format is {@code yyyy-MM-dd hh:mm:ss a}, for example: "2025-07-11 12:13:45 pm".
     *
     * @return The formatted date and time string.
     */
    private String getCurrentFormattedDateTime() {
        // Get the current date and time
        LocalDateTime now = LocalDateTime.now();

        // Define the desired format. Using Locale.US ensures the "AM/PM" marker.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM"
                        + "-dd hh:mm:ss a",
                Locale.US);

        // Format the date/time and convert the AM/PM part to lowercase to match "pm".
        return now.format(formatter).toLowerCase();
    }
}
