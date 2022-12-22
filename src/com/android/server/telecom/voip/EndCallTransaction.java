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

package com.android.server.telecom.voip;

import android.telecom.DisconnectCause;
import android.util.Log;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * This transaction should only be created for a CallControl action.
 */
public class EndCallTransaction extends VoipCallTransaction {
    private static final String TAG = EndCallTransaction.class.getSimpleName();
    private final CallsManager mCallsManager;
    private final boolean mIsDisconnect;
    private final int mCode;
    private final Call mCall;

    public EndCallTransaction(CallsManager callsManager, boolean isDisconnect, int code,
            Call call) {
        mCallsManager = callsManager;
        mIsDisconnect = isDisconnect;
        mCode = code;
        mCall = call;
    }

    @Override
    public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, String.format("processTransaction: isDisconnect=[%b]", mIsDisconnect));

        if (mIsDisconnect) {
            mCallsManager.markCallAsDisconnected(mCall, new DisconnectCause(mCode));
        } else {
            mCallsManager.rejectCall(mCall, mCode);
        }
        mCallsManager.markCallAsRemoved(mCall);

        return CompletableFuture.completedFuture(
                new VoipCallTransactionResult(VoipCallTransactionResult.RESULT_SUCCEED,
                        "endCallTransaction complete"));
    }
}
