/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Handler;
import android.os.Looper;
import android.telecom.CallEndpoint;
import android.telecom.VideoProfile;

import com.android.internal.telecom.ICallEndpointCallback;

public class CallEndpointSessionTracker {
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Call mCall;
    private final CallEndpoint mCallEndpoint;
    private final int mRequestType;
    private final @VideoProfile.VideoState int mVideoState;
    private InternalCallEndpointSession mSession;
    private ICallEndpointCallback mCallEndpointCallback;
    private boolean mRequestHandled;

    public CallEndpointSessionTracker(InternalCallEndpointSession internalCallEndpointSession,
            int requestType) {
        this(internalCallEndpointSession, requestType, 0 /* VideoState */);
    }

    public CallEndpointSessionTracker(InternalCallEndpointSession internalCallEndpointSession,
            int requestType, @VideoProfile.VideoState int videoState) {
        mSession = internalCallEndpointSession;
        mCall = mSession.getCall();
        mCallEndpoint = mSession.getCallEndpoint();
        mRequestType = requestType;
        mVideoState = videoState;
        mRequestHandled = false;
    }

    public InternalCallEndpointSession getSession() {
        return mSession;
    }

    public void setCallEndpointCallback(ICallEndpointCallback callEndpointCallback) {
        mCallEndpointCallback = callEndpointCallback;
    }

    public ICallEndpointCallback getCallEndpointCallback() {
        return mCallEndpointCallback;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public boolean isRequestHandled() {
        return mRequestHandled;
    }

    public void setRequestHandled(boolean requestHandled) {
        mRequestHandled = requestHandled;
    }

    public CallEndpoint getCallEndpoint() {
        return mCallEndpoint;
    }

    public int getRequestType() {
        return mRequestType;
    }

    public @VideoProfile.VideoState int getVideoState() {
        return mVideoState;
    }

    public Call getCall() {
        return mCall;
    }
}
