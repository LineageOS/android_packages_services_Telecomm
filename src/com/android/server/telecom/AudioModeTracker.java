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
 * limitations under the License.
 */

package com.android.server.telecom;

import android.media.AudioManager;
import android.telecom.Log;
import android.util.LocalLog;

import com.android.internal.util.IndentingPrintWriter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Tracks the current audio mode from {@link AudioManager} and dispatches changes to
 * multiple listeners within the Telecom framework. This class centralizes the listening logic
 * so that individual components do not need to register their own listeners with AudioManager.
 * <p>
 * Note: Initially only used for {@link LocalVoicemailController} but I'm envisioning we can use
 * this to also track audio mode changes made by VoIP calls not using Telecom to improve the
 * efficacy of {@link CallAudioWatchdog}.
 */
public class AudioModeTracker implements AudioManager.OnModeChangedListener {
    private final LocalLog mLocalLog = new LocalLog(10);

    /**
     * Interface for components that need to be notified of audio mode changes.
     */
    public interface AudioModeListener {
        /**
         * Called when the audio mode has changed.
         *
         * @param mode The new audio mode. See {@link AudioManager.AudioMode}.
         */
        void onAudioModeChanged(int mode);
    }

    private final TelecomSystem.SyncRoot mLock;
    private final List<AudioModeListener> mListeners = new CopyOnWriteArrayList<>();
    private int mCurrentAudioMode;

    /**
     * Constructs a new AudioModeTracker.
     *
     * @param audioManager The AudioManager instance to listen to.
     * @param executor     The executor on which to receive listener callbacks.
     * @param lock         The Telecom sync-root lock.
     */
    public AudioModeTracker(AudioManager audioManager, Executor executor,
            TelecomSystem.SyncRoot lock) {
        mLock = lock;
        // Cache the initial audio mode.
        mCurrentAudioMode = audioManager.getMode();
        // Register this class as the single listener for audio mode changes.
        audioManager.addOnModeChangedListener(executor, this);
    }

    /**
     * The callback received from {@link AudioManager} when the audio mode changes.
     * This method updates the cached state and notifies all registered internal listeners.
     *
     * @param mode The new audio mode.
     */
    @Override
    public void onModeChanged(int mode) {
        Log.startSession("AMT.oMC");
        try {
            synchronized (mLock) {
                if (mCurrentAudioMode == mode) {
                    return; // No change.
                }
                final int oldMode = mCurrentAudioMode;
                mCurrentAudioMode = mode;

                String modeChangeLog = String.format("%s -> %s", audioModeToString(oldMode),
                        audioModeToString(mode));
                Log.i(this, "onModeChanged: " + modeChangeLog);
                mLocalLog.log(modeChangeLog);
                for (AudioModeListener listener : mListeners) {
                    listener.onAudioModeChanged(mode);
                }
            }
        } finally {
            Log.endSession();
        }
    }

    /**
     * @return The most recently cached audio mode.
     */
    public int getAudioMode() {
        synchronized (mLock) {
            return mCurrentAudioMode;
        }
    }

    /**
     * Registers a listener to receive audio mode change notifications.
     *
     * @param listener The listener to add.
     */
    public void addListener(AudioModeListener listener) {
        if (listener != null) {
            mListeners.add(listener);
        }
    }

    /**
     * Unregisters a listener that was previously added.
     *
     * @param listener The listener to remove.
     */
    public void removeListener(AudioModeListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    /**
     * Translates an audio mode to a human readable string.
     *
     * @param mode The audio mode to translate.
     * @return The string representation of the audio mode.
     */
    public static String audioModeToString(int mode) {
        switch (mode) {
            case AudioManager.MODE_INVALID:
                return "MODE_INVALID";
            case AudioManager.MODE_CURRENT:
                return "MODE_CURRENT";
            case AudioManager.MODE_NORMAL:
                return "MODE_NORMAL";
            case AudioManager.MODE_RINGTONE:
                return "MODE_RINGTONE";
            case AudioManager.MODE_IN_CALL:
                return "MODE_IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION:
                return "MODE_IN_COMMUNICATION";
            case AudioManager.MODE_CALL_SCREENING:
                return "MODE_CALL_SCREENING";
            case AudioManager.MODE_CALL_REDIRECT:
                return "MODE_CALL_REDIRECT";
            case AudioManager.MODE_COMMUNICATION_REDIRECT:
                return "MODE_COMMUNICATION_REDIRECT";
            default:
                return "MODE_UNKNOWN_" + mode;
        }
    }

    /**
     * Dumps the state of the {@link AudioModeTracker}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Audio Mode History:");
        pw.increaseIndent();
        mLocalLog.dump(pw);
        pw.decreaseIndent();
    }
}