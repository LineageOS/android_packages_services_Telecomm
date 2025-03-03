/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.os.OutcomeReceiver;
import android.telecom.CallException;
import android.telecom.DisconnectCause;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.callsequencing.voip.EndCallTransaction;
import com.android.server.telecom.callsequencing.voip.HoldCallTransaction;
import com.android.server.telecom.callsequencing.voip.MaybeHoldCallForNewCallTransaction;
import com.android.server.telecom.callsequencing.voip.RequestNewActiveCallTransaction;
import com.android.server.telecom.callsequencing.voip.SerialTransaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Helper adapter class used to centralized code that will be affected by toggling the
 * {@link Flags#enableCallSequencing()} flag.
 */
public class TransactionalCallSequencingAdapter {
    private final TransactionManager mTransactionManager;
    private final CallsManager mCallsManager;
    private final boolean mIsCallSequencingEnabled;

    public TransactionalCallSequencingAdapter(TransactionManager transactionManager,
            CallsManager callsManager, boolean isCallSequencingEnabled) {
        mTransactionManager = transactionManager;
        mCallsManager = callsManager;
        mIsCallSequencingEnabled = isCallSequencingEnabled;
    }

    /**
     * Client -> Server request to set a call active
     */
    public void setActive(Call call,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        if (mIsCallSequencingEnabled) {
            createSetActiveTransactionSequencing(call, true /* callControlRequest */, null,
                    receiver, receiver);
        } else {
            mTransactionManager.addTransaction(createSetActiveTransactions(call,
                    true /* callControlRequest */), receiver);
        }
    }

    /**
     * Client -> Server request to answer a call
     */
    public void setAnswered(Call call, int newVideoState,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        boolean isCallControlRequest = true;
        OutcomeReceiver<CallTransactionResult, CallException> receiverForTransaction =
                getSetAnswerReceiver(call, null /* foregroundCallBeforeSwap */,
                        false /* wasForegroundActive */, newVideoState, receiver,
                        isCallControlRequest);
        if (mIsCallSequencingEnabled) {
            createSetActiveTransactionSequencing(call, isCallControlRequest, null,
                    receiver, receiverForTransaction /* receiverForTransaction */);
        } else {
            mTransactionManager.addTransaction(createSetActiveTransactions(call,
                    isCallControlRequest), receiverForTransaction);
        }
    }

    /**
     * Client -> Server request to set a call to disconnected
     */
    public void setDisconnected(Call call, DisconnectCause dc,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        mTransactionManager.addTransaction(
                new EndCallTransaction(mCallsManager, dc, call), receiver);
    }

    /**
     * Client -> Server request to set a call to inactive
     */
    public void setInactive(Call call,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        mTransactionManager.addTransaction(new HoldCallTransaction(mCallsManager,call), receiver);
    }

    /**
     * Server -> Client command to set the call active, which if it fails, will try to reset the
     * state to what it was before the call was set to active.
     */
    public CompletableFuture<Boolean> onSetActive(Call call,
            CallTransaction clientCbT,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        // save CallsManager state before sending client state changes
        Call foregroundCallBeforeSwap = mCallsManager.getForegroundCall();
        boolean wasActive = foregroundCallBeforeSwap != null && foregroundCallBeforeSwap.isActive();
        OutcomeReceiver<CallTransactionResult, CallException> receiverForTransaction =
                getOnSetActiveReceiver(call, foregroundCallBeforeSwap, wasActive, receiver);

        if (mIsCallSequencingEnabled) {
            return createSetActiveTransactionSequencing(call, false /* callControlRequest */,
                    clientCbT, receiver, receiverForTransaction);
        } else {
            SerialTransaction serialTransactions = createSetActiveTransactions(call,
                    false /* callControlRequest */);
            serialTransactions.appendTransaction(clientCbT);
            // do CallsManager workload before asking client and
            //   reset CallsManager state if client does NOT ack
            return mTransactionManager.addTransaction(
                    serialTransactions, receiverForTransaction);
        }
    }

    /**
     * Server -> Client command to answer an incoming call, which if it fails, will trigger the
     * disconnect of the call and then reset the state of the other call back to what it was before.
     */
    public CompletableFuture<Boolean> onSetAnswered(Call call, int videoState,
            CallTransaction clientCbT, OutcomeReceiver<CallTransactionResult,
            CallException> receiver) {
        boolean isCallControlRequest = false;
        // save CallsManager state before sending client state changes
        Call foregroundCallBeforeSwap = mCallsManager.getForegroundCall();
        boolean wasActive = foregroundCallBeforeSwap != null && foregroundCallBeforeSwap.isActive();
        OutcomeReceiver<CallTransactionResult, CallException> receiverForTransaction =
                getSetAnswerReceiver(call, foregroundCallBeforeSwap, wasActive,
                        videoState, receiver, isCallControlRequest);

        if (mIsCallSequencingEnabled) {
            return createSetActiveTransactionSequencing(call, false /* callControlRequest */,
                    clientCbT, receiver, receiverForTransaction);
        } else {
            SerialTransaction serialTransactions = createSetActiveTransactions(call,
                    isCallControlRequest);
            serialTransactions.appendTransaction(clientCbT);
            // do CallsManager workload before asking client and
            //   reset CallsManager state if client does NOT ack
            return mTransactionManager.addTransaction(serialTransactions, receiverForTransaction);
        }
    }

    /**
     * Server -> Client command to set the call as inactive
     */
    public CompletableFuture<Boolean> onSetInactive(Call call,
            CallTransaction clientCbT,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        return mTransactionManager.addTransaction(clientCbT,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(CallTransactionResult callTransactionResult) {
                        mCallsManager.markCallAsOnHold(call);
                        receiver.onResult(callTransactionResult);
                    }

                    @Override
                    public void onError(CallException error) {
                        receiver.onError(error);
                    }
                });
    }

    /**
     * Server -> Client command to disconnect the call
     */
    public CompletableFuture<Boolean> onSetDisconnected(Call call,
            DisconnectCause dc, CallTransaction clientCbT, OutcomeReceiver<CallTransactionResult,
            CallException> receiver) {
        return mTransactionManager.addTransaction(clientCbT,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(CallTransactionResult result) {
                        removeCallFromCallsManager(call, dc);
                        receiver.onResult(result);
                    }

                    @Override
                    public void onError(CallException exception) {
                        removeCallFromCallsManager(call, dc);
                        receiver.onError(exception);
                    }
                });
    }

    /**
     * Clean up the calls that have been passed in from CallsManager
     */
    public void cleanup(Collection<Call> calls) {
        cleanupFlagOff(calls);
    }

    private SerialTransaction createSetActiveTransactions(Call call, boolean isCallControlRequest) {
        // create list for multiple transactions
        List<CallTransaction> transactions = new ArrayList<>();

        // potentially hold the current active call in order to set a new call (active/answered)
        transactions.add(new MaybeHoldCallForNewCallTransaction(mCallsManager, call,
                isCallControlRequest));
        // And request a new focus call update
        transactions.add(new RequestNewActiveCallTransaction(mCallsManager, call));

        return new SerialTransaction(transactions, mCallsManager.getLock());
    }

    /**
     * This code path is invoked when mIsCallSequencingEnabled is true. We will first try to hold
     * the active call before adding the transactions to request call focus for the new call as well
     * as verify the client ack for the transaction (if applicable). If the hold transaction
     * succeeds, we will continue processing the rest of the transactions via a SerialTransaction.
     */
    private CompletableFuture<Boolean> createSetActiveTransactionSequencing(
            Call call, boolean isCallControlRequest, CallTransaction clientCbT,
            OutcomeReceiver<CallTransactionResult, CallException> receiver,
            OutcomeReceiver<CallTransactionResult, CallException> receiverForTransaction) {
        final CompletableFuture<Boolean>[] createSetActiveFuture =
                new CompletableFuture[]{new CompletableFuture<>()};
        OutcomeReceiver<Boolean, CallException> maybePerformHoldCallback = new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                // Transaction not yet completed. Still need to request focus for active call and
                // process client callback transaction if applicable.
                // create list for multiple transactions
                List<CallTransaction> transactions = new ArrayList<>();
                // And request a new focus call update
                transactions.add(new RequestNewActiveCallTransaction(mCallsManager, call));
                if (clientCbT != null){
                    transactions.add(clientCbT);
                }
                SerialTransaction serialTransactions = new SerialTransaction(
                        transactions, mCallsManager.getLock());
                createSetActiveFuture[0] = mTransactionManager.addTransaction(serialTransactions,
                        receiverForTransaction);
            }

            @Override
            public void onError(CallException exception) {
                createSetActiveFuture[0] = CompletableFuture.completedFuture(false);
                receiver.onError(exception);
            }
        };

        mCallsManager.getCallSequencingAdapter().transactionHoldPotentialActiveCallForNewCall(call,
                isCallControlRequest, maybePerformHoldCallback);
        return createSetActiveFuture[0];
    }

    private void removeCallFromCallsManager(Call call, DisconnectCause cause) {
        mCallsManager.markCallAsDisconnected(call, cause);
        mCallsManager.removeCall(call);
    }

    private void maybeResetForegroundCall(Call foregroundCallBeforeSwap, boolean wasActive) {
        if (foregroundCallBeforeSwap == null) {
            return;
        }
        if (wasActive && !foregroundCallBeforeSwap.isActive()) {
            mCallsManager.markCallAsActive(foregroundCallBeforeSwap);
        }
    }
    private void cleanupFlagOff(Collection<Call> calls) {
        for (Call call : calls) {
            mCallsManager.markCallAsDisconnected(call,
                    new DisconnectCause(DisconnectCause.ERROR, "process died"));
            mCallsManager.removeCall(call); // This will clear mTrackedCalls && ClientTWS
        }
    }

    private OutcomeReceiver<CallTransactionResult, CallException> getOnSetActiveReceiver(
            Call call, Call foregroundCallBeforeSwap, boolean wasForegroundActive,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(CallTransactionResult result) {
                receiver.onResult(result);
            }

            @Override
            public void onError(CallException exception) {
                mCallsManager.markCallAsOnHold(call);
                maybeResetForegroundCall(foregroundCallBeforeSwap, wasForegroundActive);
                receiver.onError(exception);
            }
        };
    }

    private OutcomeReceiver<CallTransactionResult, CallException> getSetAnswerReceiver(
            Call call, Call foregroundCallBeforeSwap, boolean wasForegroundActive, int videoState,
            OutcomeReceiver<CallTransactionResult, CallException> receiver,
            boolean isCallControlRequest) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(CallTransactionResult result) {
                call.setVideoState(videoState);
                receiver.onResult(result);
            }

            @Override
            public void onError(CallException exception) {
                if (!isCallControlRequest) {
                    // This also sends the signal to untrack from TSW and the
                    // client_TSW
                    removeCallFromCallsManager(call,
                            new DisconnectCause(DisconnectCause.REJECTED,
                                    "client rejected to answer the call;"
                                            + " force disconnecting"));
                    maybeResetForegroundCall(foregroundCallBeforeSwap, wasForegroundActive);
                }
                receiver.onError(exception);
            }
        };
    }
}
