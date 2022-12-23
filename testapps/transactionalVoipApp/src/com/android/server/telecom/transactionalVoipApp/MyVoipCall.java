/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.telecom.transactionalVoipApp;


import android.telecom.CallAudioState;
import android.telecom.CallControl;
import android.telecom.CallEventCallback;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.function.Consumer;

public class MyVoipCall implements CallEventCallback {

    private static final String TAG = "MyVoipCall";
    private final String mCallId;
    CallControl mCallControl;

    MyVoipCall(String id) {
        mCallId = id;
    }

    public void onAddCallControl(@NonNull CallControl callControl) {
        mCallControl = callControl;
    }

    @Override
    public void onSetActive(@NonNull Consumer<Boolean> wasCompleted) {
        Log.i(TAG, String.format("onSetActive: callId=[%s]", mCallId));
        wasCompleted.accept(Boolean.TRUE);
    }

    @Override
    public void onSetInactive(@NonNull Consumer<Boolean> wasCompleted) {
        Log.i(TAG, String.format("onSetInactive: callId=[%s]", mCallId));
        wasCompleted.accept(Boolean.TRUE);
    }

    @Override
    public void onAnswer(int videoState, @NonNull Consumer<Boolean> wasCompleted) {
        Log.i(TAG, String.format("onAnswer: callId=[%s]", mCallId));
        wasCompleted.accept(Boolean.TRUE);
    }

    @Override
    public void onReject(@NonNull Consumer<Boolean> wasCompleted) {
        Log.i(TAG, String.format("onReject: callId=[%s]", mCallId));
        wasCompleted.accept(Boolean.TRUE);
    }

    @Override
    public void onDisconnect(@NonNull Consumer<Boolean> wasCompleted) {
        Log.i(TAG, String.format("onDisconnect: callId=[%s]", mCallId));
        wasCompleted.accept(Boolean.TRUE);
    }

    @Override
    public void onCallAudioStateChanged(@NonNull CallAudioState callAudioState) {
        Log.i(TAG, String.format("onCallAudioStateChanged: state=[%s]", callAudioState.toString()));
    }

    @Override
    public void onCallStreamingStarted(@NonNull Consumer<Boolean> wasCompleted) {
        Log.i(TAG, String.format("onCallStreamingStarted: callId=[%s]", mCallId));
    }

    @Override
    public void onCallStreamingFailed(int reason) {
        Log.i(TAG, String.format("onCallStreamingFailed: callId[%s], reason=[%s]", mCallId,
                reason));
    }
}
