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

package com.android.server.telecom;

import static android.telecom.CallException.CODE_CALL_IS_NOT_BEING_TRACKED;
import static android.telecom.CallException.TRANSACTION_EXCEPTION_KEY;
import static android.telecom.TelecomManager.TELECOM_TRANSACTION_SUCCESS;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.telecom.CallEndpoint;
import android.telecom.CallException;
import android.telecom.CallStreamingService;
import android.telecom.DisconnectCause;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;

import androidx.annotation.VisibleForTesting;

import com.android.internal.telecom.ICallControl;
import com.android.internal.telecom.ICallEventCallback;
import com.android.server.telecom.voip.AnswerCallTransaction;
import com.android.server.telecom.voip.CallEventCallbackAckTransaction;
import com.android.server.telecom.voip.EndpointChangeTransaction;
import com.android.server.telecom.voip.HoldCallTransaction;
import com.android.server.telecom.voip.EndCallTransaction;
import com.android.server.telecom.voip.HoldActiveCallForNewCallTransaction;
import com.android.server.telecom.voip.ParallelTransaction;
import com.android.server.telecom.voip.RequestFocusTransaction;
import com.android.server.telecom.voip.SerialTransaction;
import com.android.server.telecom.voip.TransactionManager;
import com.android.server.telecom.voip.VoipCallTransaction;
import com.android.server.telecom.voip.VoipCallTransactionResult;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Implements {@link android.telecom.CallEventCallback} and {@link android.telecom.CallControl}
 * on a per-client basis which is tied to a {@link PhoneAccountHandle}
 */
public class TransactionalServiceWrapper implements
        ConnectionServiceFocusManager.ConnectionServiceFocus, IBinder.DeathRecipient {
    private static final String TAG = TransactionalServiceWrapper.class.getSimpleName();

    // CallControl : Client (ex. voip app) --> Telecom
    public static final String SET_ACTIVE = "SetActive";
    public static final String SET_INACTIVE = "SetInactive";
    public static final String ANSWER = "Answer";
    public static final String DISCONNECT = "Disconnect";
    public static final String START_STREAMING = "StartStreaming";

    // CallEventCallback : Telecom --> Client (ex. voip app)
    public static final String ON_SET_ACTIVE = "onSetActive";
    public static final String ON_SET_INACTIVE = "onSetInactive";
    public static final String ON_ANSWER = "onAnswer";
    public static final String ON_DISCONNECT = "onDisconnect";
    public static final String ON_STREAMING_STARTED = "onStreamingStarted";

    private final CallsManager mCallsManager;
    private final ICallEventCallback mICallEventCallback;
    private final PhoneAccountHandle mPhoneAccountHandle;
    private final TransactionalServiceRepository mRepository;
    private ConnectionServiceFocusManager.ConnectionServiceFocusListener mConnSvrFocusListener;
    // init when constructor is called
    private final Hashtable<String, Call> mTrackedCalls = new Hashtable<>();
    private final TelecomSystem.SyncRoot mLock;
    private final String mPackageName;
    // needs to be non-final for testing
    private TransactionManager mTransactionManager;
    private CallStreamingController mStreamingController;

    public TransactionalServiceWrapper(ICallEventCallback callEventCallback,
            CallsManager callsManager, PhoneAccountHandle phoneAccountHandle, Call call,
            TransactionalServiceRepository repo) {
        // passed args
        mICallEventCallback = callEventCallback;
        mCallsManager = callsManager;
        mPhoneAccountHandle = phoneAccountHandle;
        mTrackedCalls.put(call.getId(), call); // service is now tracking its first call
        mRepository = repo;
        // init instance vars
        mPackageName = phoneAccountHandle.getComponentName().getPackageName();
        mTransactionManager = TransactionManager.getInstance();
        mStreamingController = mCallsManager.getCallStreamingController();
        mLock = mCallsManager.getLock();
    }

    @VisibleForTesting
    public void setTransactionManager(TransactionManager transactionManager) {
        mTransactionManager = transactionManager;
    }

    public TransactionManager getTransactionManager() {
        return mTransactionManager;
    }

    public PhoneAccountHandle getPhoneAccountHandle() {
        return mPhoneAccountHandle;
    }

    public void trackCall(Call call) {
        synchronized (mLock) {
            if (call != null) {
                mTrackedCalls.put(call.getId(), call);
            }
        }
    }

    public Call getCallById(String callId) {
        synchronized (mLock) {
            return mTrackedCalls.get(callId);
        }
    }

    @VisibleForTesting
    public boolean untrackCall(Call call) {
        Call removedCall = null;
        synchronized (mLock) {
            if (call != null) {
                removedCall = mTrackedCalls.remove(call.getId());
                if (mTrackedCalls.size() == 0) {
                    mRepository.removeServiceWrapper(mPhoneAccountHandle);
                }
            }
        }
        Log.i(TAG, "removedCall call=" + removedCall);
        return removedCall != null;
    }

    @VisibleForTesting
    public int getNumberOfTrackedCalls() {
        int callCount = 0;
        synchronized (mLock) {
            callCount = mTrackedCalls.size();
        }
        return callCount;
    }

    @Override
    public void binderDied() {
        // remove all tacked calls from CallsManager && frameworks side
        for (String id : mTrackedCalls.keySet()) {
            Call call = mTrackedCalls.get(id);
            mCallsManager.markCallAsDisconnected(call, new DisconnectCause(DisconnectCause.ERROR));
            mCallsManager.removeCall(call);
            // remove calls from Frameworks side
            if (mICallEventCallback != null) {
                try {
                    mICallEventCallback.removeCallFromTransactionalServiceWrapper(call.getId());
                } catch (RemoteException e) {
                    // pass
                }
            }
        }
        mTrackedCalls.clear();
    }

    /***
     *********************************************************************************************
     **                        ICallControl: Client --> Server                                **
     **********************************************************************************************
     */
    public final ICallControl mICallControl = new ICallControl.Stub() {
        @Override
        public void setActive(String callId, android.os.ResultReceiver callback)
                throws RemoteException {
            try {
                Log.startSession("TSW.sA");
                createTransactions(callId, callback, SET_ACTIVE);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void answer(int videoState, String callId, android.os.ResultReceiver callback)
                throws RemoteException {
            try {
                Log.startSession("TSW.a");
                createTransactions(callId, callback, ANSWER, videoState);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void setInactive(String callId, android.os.ResultReceiver callback)
                throws RemoteException {
            try {
                Log.startSession("TSW.sI");
                createTransactions(callId, callback, SET_INACTIVE);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void disconnect(String callId, DisconnectCause disconnectCause,
                android.os.ResultReceiver callback)
                throws RemoteException {
            try {
                Log.startSession("TSW.d");
                createTransactions(callId, callback, DISCONNECT, disconnectCause);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void startCallStreaming(String callId, android.os.ResultReceiver callback)
                throws RemoteException {
            try {
                Log.startSession("TSW.sCS");
                createTransactions(callId, callback, START_STREAMING);
            } finally {
                Log.endSession();
            }
        }

        private void createTransactions(String callId, ResultReceiver callback, String action,
                Object... objects) {
            Log.d(TAG, "createTransactions: callId=" + callId);
            Call call = mTrackedCalls.get(callId);
            if (call != null) {
                switch (action) {
                    case SET_ACTIVE:
                        addTransactionsToManager(createSetActiveTransactions(call), callback);
                        break;
                    case ANSWER:
                        addTransactionsToManager(createSetAnswerTransactions(call,
                                (int) objects[0]), callback);
                        break;
                    case DISCONNECT:
                        addTransactionsToManager(new EndCallTransaction(mCallsManager,
                                (DisconnectCause) objects[0], call), callback);
                        break;
                    case SET_INACTIVE:
                        addTransactionsToManager(
                                new HoldCallTransaction(mCallsManager, call), callback);
                        break;
                    case START_STREAMING:
                        addTransactionsToManager(createStartStreamingTransaction(call), callback);
                        break;
                }
            } else {
                Bundle exceptionBundle = new Bundle();
                exceptionBundle.putParcelable(TRANSACTION_EXCEPTION_KEY,
                        new CallException(TextUtils.formatSimple(
                        "Telecom cannot process [%s] because the call with id=[%s] is no longer "
                                + "being tracked. This is most likely a result of the call "
                                + "already being disconnected and removed. Try re-adding the call"
                                + " via TelecomManager#addCall", action, callId),
                                CODE_CALL_IS_NOT_BEING_TRACKED));
                callback.send(CODE_CALL_IS_NOT_BEING_TRACKED, exceptionBundle);
            }
        }

        @Override
        public void requestCallEndpointChange(CallEndpoint endpoint, ResultReceiver callback) {
            try {
                Log.startSession("TSW.rCEC");
                addTransactionsToManager(new EndpointChangeTransaction(endpoint, mCallsManager),
                        callback);
            } finally {
                Log.endSession();
            }
        }

        /**
         * Application would like to inform InCallServices of an event
         */
        @Override
        public void sendEvent(String callId, String event, Bundle extras) {
            try {
                Log.startSession("TSW.sE");
                Call call = mTrackedCalls.get(callId);
                if (call != null) {
                    call.onConnectionEvent(event, extras);
                }
                else{
                    Log.i(TAG,
                            "sendEvent: was called but there is no call with id=[%s] cannot be "
                                    + "found. Most likely the call has been disconnected");
                }
            }
            finally {
                Log.endSession();
            }
        }
    };

    private void addTransactionsToManager(VoipCallTransaction transaction,
            ResultReceiver callback) {
        Log.d(TAG, "addTransactionsToManager");

        mTransactionManager.addTransaction(transaction, new OutcomeReceiver<>() {
            @Override
            public void onResult(VoipCallTransactionResult result) {
                Log.d(TAG, "addTransactionsToManager: onResult:");
                callback.send(TELECOM_TRANSACTION_SUCCESS, new Bundle());
            }

            @Override
            public void onError(CallException exception) {
                Log.d(TAG, "addTransactionsToManager: onError");
                Bundle extras = new Bundle();
                extras.putParcelable(TRANSACTION_EXCEPTION_KEY, exception);
                callback.send(exception == null ? CallException.CODE_ERROR_UNKNOWN :
                        exception.getCode(), extras);
            }
        });
    }

    public ICallControl getICallControl() {
        return mICallControl;
    }

    /***
     *********************************************************************************************
     **                    ICallEventCallback: Server --> Client                                **
     **********************************************************************************************
     */

    public void onSetActive(Call call) {
        try {
            Log.startSession("TSW.oSA");
            Log.d(TAG, String.format(Locale.US, "onSetActive: callId=[%s]", call.getId()));
            handleNewActiveCallCallbacks(call, ON_SET_ACTIVE, 0);
        } finally {
            Log.endSession();
        }
    }

    public void onAnswer(Call call, int videoState) {
        try {
            Log.startSession("TSW.oA");
            Log.d(TAG, String.format(Locale.US, "onAnswer: callId=[%s]", call.getId()));
            handleNewActiveCallCallbacks(call, ON_ANSWER, videoState);
        } finally {
            Log.endSession();
        }
    }

    // need to create multiple transactions for onSetActive and onAnswer which both seek to set
    // the call to active
    private void handleNewActiveCallCallbacks(Call call, String action, int videoState) {
        // save CallsManager state before sending client state changes
        Call foregroundCallBeforeSwap = mCallsManager.getForegroundCall();
        boolean wasActive = foregroundCallBeforeSwap != null && foregroundCallBeforeSwap.isActive();

        // create 3 serial transactions:
        // -- hold active
        // -- set newCall as active
        // -- ack from client
        SerialTransaction serialTransactions = createSetActiveTransactions(call);
        serialTransactions.appendTransaction(
                new CallEventCallbackAckTransaction(mICallEventCallback,
                        action, call.getId(), videoState, mLock));

        // do CallsManager workload before asking client and
        //   reset CallsManager state if client does NOT ack
        mTransactionManager.addTransaction(serialTransactions, new OutcomeReceiver<>() {
            @Override
            public void onResult(VoipCallTransactionResult result) {
                Log.i(TAG, String.format(Locale.US,
                        "%s: onResult: callId=[%s]", action, call.getId()));
            }

            @Override
            public void onError(CallException exception) {
                maybeResetForegroundCall(foregroundCallBeforeSwap, wasActive);
            }
        });
    }


    public void onSetInactive(Call call) {
        try {
            Log.startSession("TSW.oSI");
            Log.i(TAG, String.format(Locale.US, "onSetInactive: callId=[%s]", call.getId()));
            mTransactionManager.addTransaction(
                    new CallEventCallbackAckTransaction(mICallEventCallback,
                            ON_SET_INACTIVE, call.getId(), mLock), new OutcomeReceiver<>() {
                        @Override
                        public void onResult(VoipCallTransactionResult result) {
                            mCallsManager.markCallAsOnHold(call);
                        }

                        @Override
                        public void onError(CallException exception) {
                            Log.i(TAG, "onSetInactive: onError: with e=[%e]", exception);
                        }
                    });
        } finally {
            Log.endSession();
        }
    }

    public void onDisconnect(Call call, DisconnectCause cause) {
        try {
            Log.startSession("TSW.oD");
            Log.d(TAG, String.format(Locale.US, "onDisconnect: callId=[%s]", call.getId()));

            mTransactionManager.addTransaction(
                    new CallEventCallbackAckTransaction(mICallEventCallback, ON_DISCONNECT,
                            call.getId(), cause, mLock), new OutcomeReceiver<>() {
                        @Override
                        public void onResult(VoipCallTransactionResult result) {
                            removeCallFromCallsManager(call, cause);
                        }

                        @Override
                        public void onError(CallException exception) {
                            removeCallFromCallsManager(call, cause);
                        }
                    }
            );
        } finally {
            Log.endSession();
        }
    }

    public void onCallStreamingStarted(Call call) {
        try {
            Log.startSession("TSW.oCSS");
            Log.d(TAG, String.format(Locale.US, "onCallStreamingStarted: callId=[%s]",
                    call.getId()));

            mTransactionManager.addTransaction(
                    new CallEventCallbackAckTransaction(mICallEventCallback, ON_STREAMING_STARTED,
                            call.getId(), mLock), new OutcomeReceiver<>() {
                        @Override
                        public void onResult(VoipCallTransactionResult result) {
                        }

                        @Override
                        public void onError(CallException exception) {
                            Log.i(TAG, "onCallStreamingStarted: onError: with e=[%e]",
                                    exception);
                            stopCallStreaming(call);
                        }
                    }
            );
        } finally {
            Log.endSession();
        }
    }

    public void onCallStreamingFailed(Call call,
            @CallStreamingService.StreamingFailedReason int streamingFailedReason) {
        if (call != null) {
            try {
                mICallEventCallback.onCallStreamingFailed(call.getId(), streamingFailedReason);
            } catch (RemoteException e) {
            }
        }
    }

    public void onCallEndpointChanged(Call call, CallEndpoint endpoint) {
        if (call != null) {
            try {
                mICallEventCallback.onCallEndpointChanged(call.getId(), endpoint);
            } catch (RemoteException e) {
            }
        }
    }

    public void onAvailableCallEndpointsChanged(Call call, Set<CallEndpoint> endpoints) {
        if (call != null) {
            try {
                mICallEventCallback.onAvailableCallEndpointsChanged(call.getId(),
                        endpoints.stream().toList());
            } catch (RemoteException e) {
            }
        }
    }

    public void onMuteStateChanged(Call call, boolean isMuted) {
        if (call != null) {
            try {
                mICallEventCallback.onMuteStateChanged(call.getId(), isMuted);
            } catch (RemoteException e) {
            }
        }
    }

    public void removeCallFromWrappers(Call call) {
        if (call != null) {
            try {
                // remove the call from frameworks wrapper (client side)
                mICallEventCallback.removeCallFromTransactionalServiceWrapper(call.getId());
                // remove the call from this class/wrapper (server side)
                untrackCall(call);
            } catch (RemoteException e) {
            }
        }
    }

    public void onEvent(Call call, String event, Bundle extras){
        if (call != null) {
            try {
                mICallEventCallback.onEvent(call.getId(), event, extras);
            } catch (RemoteException e) {
            }
        }
    }

    /***
     *********************************************************************************************
     **                                Helpers                                                  **
     **********************************************************************************************
     */
    private void maybeResetForegroundCall(Call foregroundCallBeforeSwap, boolean wasActive) {
        if (foregroundCallBeforeSwap == null) {
            return;
        }
        if (wasActive && !foregroundCallBeforeSwap.isActive()) {
            mCallsManager.markCallAsActive(foregroundCallBeforeSwap);
        }
    }

    private void removeCallFromCallsManager(Call call, DisconnectCause cause) {
        if (cause.getCode() != DisconnectCause.REJECTED) {
            mCallsManager.markCallAsDisconnected(call, cause);
        }
        mCallsManager.removeCall(call);
    }

    private SerialTransaction createSetActiveTransactions(Call call) {
        // create list for multiple transactions
        List<VoipCallTransaction> transactions = new ArrayList<>();

        // add t1. hold potential active call
        transactions.add(new HoldActiveCallForNewCallTransaction(mCallsManager, call));

        // add t2. send request to set the current call active
        transactions.add(new RequestFocusTransaction(mCallsManager, call));

        // send off to Transaction Manager to process
        return new SerialTransaction(transactions, mLock);
    }

    private SerialTransaction createSetAnswerTransactions(Call call, int videoState) {
        // create list for multiple transactions
        List<VoipCallTransaction> transactions = new ArrayList<>();

        // add t1. hold potential active call
        transactions.add(new HoldActiveCallForNewCallTransaction(mCallsManager, call));

        // add t2. answer current call
        transactions.add(new AnswerCallTransaction(mCallsManager, call, videoState));

        // send off to Transaction Manager to process
        return new SerialTransaction(transactions, mLock);
    }

    /***
     *********************************************************************************************
     **                    FocusManager                                                       **
     **********************************************************************************************
     */

    @Override
    public void connectionServiceFocusLost() {
        if (mConnSvrFocusListener != null) {
            mConnSvrFocusListener.onConnectionServiceReleased(this);
        }
        Log.i(TAG, String.format(Locale.US, "connectionServiceFocusLost for package=[%s]",
                mPackageName));
    }

    @Override
    public void connectionServiceFocusGained() {
        Log.i(TAG, String.format(Locale.US, "connectionServiceFocusGained for package=[%s]",
                mPackageName));
    }

    @Override
    public void setConnectionServiceFocusListener(
            ConnectionServiceFocusManager.ConnectionServiceFocusListener listener) {
        mConnSvrFocusListener = listener;
    }

    @Override
    public ComponentName getComponentName() {
        return mPhoneAccountHandle.getComponentName();
    }

    /***
     *********************************************************************************************
     **                    CallStreaming                                                        **
     *********************************************************************************************
     */

    private SerialTransaction createStartStreamingTransaction(Call call) {
        // start streaming transaction flow:
        //     make sure there's no ongoing streaming call --> bind to EXO
        //                                                 `-> change audio mode
        // create list for multiple transactions
        List<VoipCallTransaction> transactions = new ArrayList<>();

        // add t1. make sure no ongoing streaming call
        transactions.add(new CallStreamingController.QueryCallStreamingTransaction(mCallsManager));

        // create list for parallel transactions
        List<VoipCallTransaction> subTransactions = new ArrayList<>();
        // add t2.1 bind to call streaming service
        subTransactions.add(mStreamingController.getCallStreamingServiceTransaction(
                mCallsManager.getContext(), this, call));
        // add t2.2 audio route operations
        subTransactions.add(new CallStreamingController.AudioInterceptionTransaction(call,
                true, mLock));

        // add t2
        transactions.add(new ParallelTransaction(subTransactions, mLock));
        // send off to Transaction Manager to process
        return new SerialTransaction(transactions, mLock);
    }

    private VoipCallTransaction createStopStreamingTransaction(Call call) {
        // TODO: implement this
        // Stop streaming transaction flow:
        List<VoipCallTransaction> transactions = new ArrayList<>();

        // 1. unbind to call streaming service
        transactions.add(mStreamingController.getUnbindStreamingServiceTransaction());
        // 2. audio route operations
        transactions.add(new CallStreamingController.AudioInterceptionTransaction(call,
                false, mLock));
        return new ParallelTransaction(transactions, mLock);
    }


    public void stopCallStreaming(Call call) {
        if (call != null && call.isStreaming()) {
            VoipCallTransaction stopStreamingTransaction = createStopStreamingTransaction(call);
            addTransactionsToManager(stopStreamingTransaction, new ResultReceiver(null));
        }
    }
}
