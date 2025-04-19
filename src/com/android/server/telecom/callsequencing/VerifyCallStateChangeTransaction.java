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

package com.android.server.telecom.callsequencing;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.flags.FeatureFlags;

import android.os.Bundle;
import android.telecom.CallException;
import android.telecom.Connection;
import android.telecom.Log;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * VerifyCallStateChangeTransaction is a transaction that verifies a CallState change and has
 * the ability to disconnect if the CallState is not changed within the timeout window.
 * <p>
 * Note: This transaction has a timeout of 2 seconds.
 */
public class VerifyCallStateChangeTransaction extends CallTransaction {
    private static final String TAG = VerifyCallStateChangeTransaction.class.getSimpleName();
    private static final long CALL_STATE_TIMEOUT_MILLISECONDS = 5000L;
    public static final String DISC_FINISH_TRANSACTION_MSG = "call disconnected while"
            + " trying to verify the following call state";
    private final Call mCall;
    private final Set<Integer> mTargetCallStates;
    private final FeatureFlags mFeatureFlags;
    private final CompletableFuture<CallTransactionResult> mTransactionResult =
            new CompletableFuture<>();

    private final Call.CallStateListener mCallStateListenerImpl = new Call.CallStateListener() {
        @Override
        public void onCallStateChanged(int newCallState) {
            Log.i(TAG, "newState=[%d], possible expected state(s)=[%s]", newCallState,
                    mTargetCallStates);
            if (mTargetCallStates.contains(newCallState)) {
                mTransactionResult.complete(new CallTransactionResult(
                        CallTransactionResult.RESULT_SUCCEED, TAG));
            }
            // NOTE:: keep listening to the call state until the timeout is reached. It's possible
            // another call state is reached in between...

            // unless the call state is disconnecting / disconnected.The transaction should be
            // cleaned up in this case because all other call states do not matter since the
            // call is being destroyed.
            if (mFeatureFlags.cleanupVerifyCallState() &&
                    isDisconnectingOrDisconnected(newCallState)) {
                if (!mTransactionResult.isDone()) {
                    mTransactionResult.complete(new CallTransactionResult(
                            CallException.CODE_ERROR_UNKNOWN, String.format("%s=[%d]",
                            DISC_FINISH_TRANSACTION_MSG, newCallState)));
                }
            }
        }
    };

    /**
     * Helper method to check if a call state is DISCONNECTED or DISCONNECTING.
     * @param callState The call state to check.
     * @return true if the state is DISCONNECTED or DISCONNECTING, false otherwise.
     */
    private static boolean isDisconnectingOrDisconnected(int callState) {
        return callState == CallState.DISCONNECTED || callState == CallState.DISCONNECTING;
    }

    private final Call.ListenerBase mCallListenerImpl = new Call.ListenerBase() {
        @Override
        public void onCallHoldFailed(Call call) {
            if (call.equals(mCall) && mTargetCallStates.contains(CallState.ON_HOLD)) {
                // Fail the transaction if a call hold failure is received.
                mTransactionResult.complete(new CallTransactionResult(
                        CallException.CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL, "error holding call"));
            }
        }
        @Override
        public void onCallResumeFailed(Call call) {
            if (call.equals(mCall) && mTargetCallStates.contains(CallState.ACTIVE)) {
                // Fail the transaction if a call resume failure is received (this means that the
                // current call could not be unheld).
                mTransactionResult.complete(new CallTransactionResult(
                        CallException.CODE_CALL_CANNOT_BE_SET_TO_ACTIVE, "error unholding call"));
            }
        }

        @Override
        public void onConnectionEvent(Call call, String event, Bundle extras) {
            // If one of the target states is disconnected and we receive a disconnect failed event
            // from Telephony, we can safely fail the transaction.
            if (call.equals(mCall) && Connection.EVENT_DISCONNECT_FAILED.equals(event)
                    && mTargetCallStates.contains(CallState.DISCONNECTED)) {
                mTransactionResult.complete(new CallTransactionResult(
                        CallException.CODE_ERROR_UNKNOWN, "error disconnecting call"));
            }
        }
    };

    public VerifyCallStateChangeTransaction(TelecomSystem.SyncRoot lock,  Call call,
            FeatureFlags featureFlags, int... targetCallStates) {
        super(lock, CALL_STATE_TIMEOUT_MILLISECONDS);
        mCall = call;
        mFeatureFlags = featureFlags;
        mTargetCallStates = IntStream.of(targetCallStates).boxed().collect(Collectors.toSet());;
    }

    @Override
    public CompletionStage<CallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction:");
        // It's possible the Call is already in the expected call state
        if (isNewCallStateTargetCallState()) {
            mTransactionResult.complete(new CallTransactionResult(
                    CallTransactionResult.RESULT_SUCCEED, TAG));
            return mTransactionResult;
        }
        mCall.addCallStateListener(mCallStateListenerImpl);
        mCall.addListener(mCallListenerImpl);
        return mTransactionResult;
    }

    @Override
    public void finishTransaction() {
        mCall.removeCallStateListener(mCallStateListenerImpl);
        mCall.removeListener(mCallListenerImpl);
    }

    private boolean isNewCallStateTargetCallState() {
        return mTargetCallStates.contains(mCall.getState());
    }

    @VisibleForTesting
    public CompletableFuture<CallTransactionResult> getTransactionResult() {
        return mTransactionResult;
    }

    @VisibleForTesting
    public Call.CallStateListener getCallStateListenerImpl() {
        return mCallStateListenerImpl;
    }
}
