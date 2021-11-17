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

import static android.telecom.Call.Callback.ANSWER_FAILED_ENDPOINT_TIMEOUT;
import static android.telecom.Call.Callback.PUSH_FAILED_ENDPOINT_TIMEOUT;

import android.os.RemoteException;
import android.telecom.CallEndpoint;
import android.telecom.Log;

import com.android.internal.telecom.ICallEndpointSession;

public class InternalCallEndpointSession extends ICallEndpointSession.Stub {
    private final Call mCall;
    private final CallEndpoint mCallEndpoint;
    private final CallEndpointController mCallEndpointController;
    private final String mPackageName;

    public InternalCallEndpointSession(Call call, CallEndpoint callEndpoint,
            CallEndpointController callEndpointController) {
        mCall = call;
        mCallEndpoint = callEndpoint;
        mCallEndpointController = callEndpointController;
        mPackageName = mCallEndpoint.getComponentName().getPackageName();
    }

    @Override
    public void setCallEndpointSessionActivated() {
        try {
            Log.startSession(LogUtils.Sessions.CES_SESSION_ACTIVATED, mPackageName);
            mCallEndpointController.onCallEndpointSessionActivated(mCall);
        } finally {
            Log.endSession();
        }
    }

    @Override
    public void setCallEndpointSessionActivationFailed(int reason) {
        try {
            Log.startSession(LogUtils.Sessions.CES_SESSION_FAILED, mPackageName);
            mCallEndpointController.onCallEndpointSessionActivationFailed(mCall, reason);
        } finally {
            Log.endSession();
        }
    }

    @Override
    public void setCallEndpointSessionDeactivated() {
        try {
            mCallEndpointController.onCallEndpointSessionDeactivated(mCall);
        } finally {
            Log.endSession();
        }
    }

    public CallEndpoint getCallEndpoint() {
        return mCallEndpoint;
    }

    public Call getCall() {
        return mCall;
    }
}
