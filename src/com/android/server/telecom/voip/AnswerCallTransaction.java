/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.OutcomeReceiver;
import android.telecom.CallException;
import android.util.Log;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * This transaction should be created for new incoming calls that request to go from
 * CallState.Ringing to CallState.Answered.  Before changing the CallState, the focus manager must
 * be updated. Once the focus manager updates, the call state will be set.  If there is an issue
 * answering the call, the transaction will fail.
 */
public class AnswerCallTransaction extends VoipCallTransaction {

    private static final String TAG = AnswerCallTransaction.class.getSimpleName();
    private final CallsManager mCallsManager;
    private final Call mCall;
    private final int mVideoState;

    public AnswerCallTransaction(CallsManager callsManager, Call call, int videoState) {
        mCallsManager = callsManager;
        mCall = call;
        mVideoState = videoState;
    }

    @Override
    public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction");
        CompletableFuture<VoipCallTransactionResult> future = new CompletableFuture<>();

        mCall.setVideoState(mVideoState);

        mCallsManager.transactionRequestNewFocusCall(mCall, CallState.ANSWERED,
                new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                Log.d(TAG, "processTransaction: onResult");
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_SUCCEED, null));
            }

            @Override
            public void onError(CallException exception) {
                Log.d(TAG, "processTransaction: onError");
                future.complete(new VoipCallTransactionResult(
                        exception.getCode(), exception.getMessage()));
            }
        });

        return future;
    }
}