/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.telecom.testapps;

import android.content.Context;
import android.media.MediaPlayer;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;

/**
 * Sample self-managed {@link Connection} for a self-managed {@link ConnectionService}.
 * <p>
 * See {@link android.telecom} for more information on self-managed {@link ConnectionService}s.
 */
public class SelfManagedConnection extends Connection {
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE =
            "com.android.server.telecom.testapps.extra.PHONE_ACCOUNT_HANDLE";

    private final SelfManagedCallList mCallList;
    private final MediaPlayer mMediaPlayer;
    private final boolean mIsIncomingCall;
    private boolean mIsIncomingCallUiShowing;

    SelfManagedConnection(SelfManagedCallList callList, Context context, boolean isIncoming) {
        mCallList = callList;
        mMediaPlayer = createMediaPlayer(context);
        mIsIncomingCall = isIncoming;
    }

    /**
     * Handles updates to the audio state of the connection.
     * @param state The new connection audio state.
     */
    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        mCallList.notifyCallModified();
    }

    public void setConnectionActive() {
        mMediaPlayer.start();
        setActive();
    }

    public void setConnectionHeld() {
        mMediaPlayer.pause();
        setOnHold();
    }

    public void setConnectionDisconnected(int cause) {
        mMediaPlayer.stop();
        setDisconnected(new DisconnectCause(cause));
        destroy();
    }

    public void setIsIncomingCallUiShowing(boolean showing) {
        mIsIncomingCallUiShowing = showing;
    }

    public boolean isIncomingCallUiShowing() {
        return mIsIncomingCallUiShowing;
    }

    public boolean isIncomingCall() {
        return mIsIncomingCall;
    }

    private MediaPlayer createMediaPlayer(Context context) {
        int audioToPlay = (Math.random() > 0.5f) ? R.raw.sample_audio : R.raw.sample_audio2;
        MediaPlayer mediaPlayer = MediaPlayer.create(context, audioToPlay);
        mediaPlayer.setLooping(true);
        return mediaPlayer;
    }
}
