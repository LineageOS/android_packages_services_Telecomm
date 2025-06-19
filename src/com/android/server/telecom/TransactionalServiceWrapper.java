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
import android.os.Binder;
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

import androidx.annotation.VisibleForTesting;

import com.android.internal.telecom.ICallControl;
import com.android.internal.telecom.ICallEventCallback;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.CallTransactionResult;
import com.android.server.telecom.callsequencing.TransactionManager;
import com.android.server.telecom.callsequencing.TransactionalCallSequencingAdapter;
import com.android.server.telecom.callsequencing.voip.CallEventCallbackAckTransaction;
import com.android.server.telecom.callsequencing.voip.EndpointChangeTransaction;
import com.android.server.telecom.callsequencing.voip.RequestVideoStateTransaction;
import com.android.server.telecom.callsequencing.voip.SetMuteStateTransaction;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements {@link android.telecom.CallEventCallback} and {@link android.telecom.CallControl}
 * on a per-client basis which is tied to a {@link PhoneAccountHandle}
 */
public class TransactionalServiceWrapper implements
        ConnectionServiceFocusManager.ConnectionServiceFocus, CallSourceService {
    private static final String TAG = TransactionalServiceWrapper.class.getSimpleName();

    // CallControl : Client (ex. voip app) --> Telecom
    public static final String SET_ACTIVE = "SetActive";
    public static final String SET_INACTIVE = "SetInactive";
    public static final String ANSWER = "Answer";
    public static final String DISCONNECT = "Disconnect";
    public static final String START_STREAMING = "StartStreaming";
    public static final String REQUEST_VIDEO_STATE = "RequestVideoState";
    public static final String SET_MUTE_STATE = "SetMuteState";
    public static final String CALL_ENDPOINT_CHANGE = "CallEndpointChange";

    // CallEventCallback : Telecom --> Client (ex. voip app)
    public static final String ON_SET_ACTIVE = "onSetActive";
    public static final String ON_SET_INACTIVE = "onSetInactive";
    public static final String ON_ANSWER = "onAnswer";
    public static final String ON_DISCONNECT = "onDisconnect";
    public static final String ON_STREAMING_STARTED = "onStreamingStarted";
    public static final String STOP_STREAMING = "stopStreaming";

    private final CallsManager mCallsManager;
    private final ICallEventCallback mICallEventCallback;
    private final PhoneAccountHandle mPhoneAccountHandle;
    private final TransactionalServiceRepository mRepository;
    private ConnectionServiceFocusManager.ConnectionServiceFocusListener mConnSvrFocusListener;
    // init when constructor is called
    private final ConcurrentHashMap<String, Call> mTrackedCalls = new ConcurrentHashMap<>();
    private final TelecomSystem.SyncRoot mLock;
    private final String mPackageName;
    // needs to be non-final for testing
    private TransactionManager mTransactionManager;
    private CallStreamingController mStreamingController;
    private final TransactionalCallSequencingAdapter mCallSequencingAdapter;
    private final FeatureFlags mFeatureFlags;
    private final AnomalyReporterAdapter mAnomalyReporter;
    public static final UUID CALL_IS_NO_LONGER_BEING_TRACKED_ERROR_UUID =
            UUID.fromString("8187cd59-97a7-4e9f-a772-638dda4b69bb");
    public static final String CALL_IS_NO_LONGER_BEING_TRACKED_ERROR_MSG =
            "A call update was attempted for a call no longer being tracked";

    // Each TransactionalServiceWrapper should have their own Binder.DeathRecipient to clean up
    // any calls in the event the application crashes or is force stopped.
    private final IBinder.DeathRecipient mAppDeathListener = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.i(TAG, "binderDied: for package=[%s]; cleaning calls", mPackageName);
            cleanupTransactionalServiceWrapper();
            mICallEventCallback.asBinder().unlinkToDeath(this, 0);
        }
    };

    public TransactionalServiceWrapper(ICallEventCallback callEventCallback,
            CallsManager callsManager, PhoneAccountHandle phoneAccountHandle, Call call,
            TransactionalServiceRepository repo, TransactionManager transactionManager,
            FeatureFlags featureFlags, AnomalyReporterAdapter anomalyReporterAdapter) {
        // passed args
        mICallEventCallback = callEventCallback;
        mCallsManager = callsManager;
        mPhoneAccountHandle = phoneAccountHandle;
        mTrackedCalls.put(call.getId(), call); // service is now tracking its first call
        mRepository = repo;
        mTransactionManager = transactionManager;
        // init instance vars
        mPackageName = phoneAccountHandle.getComponentName().getPackageName();
        mStreamingController = mCallsManager.getCallStreamingController();
        mLock = mCallsManager.getLock();
        mCallSequencingAdapter = new TransactionalCallSequencingAdapter(mTransactionManager,
                mCallsManager);
        setDeathRecipient(callEventCallback);
        mFeatureFlags = featureFlags;
        mAnomalyReporter = anomalyReporterAdapter;
    }

    public TransactionManager getTransactionManager() {
        return mTransactionManager;
    }

    @VisibleForTesting
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

    private void cleanupTransactionalServiceWrapper() {
        mCallSequencingAdapter.cleanup(mTrackedCalls.values());
    }

    /***
     *********************************************************************************************
     **                        ICallControl: Client --> Server                                **
     **********************************************************************************************
     */
    private final ICallControl mICallControl = new ICallControl.Stub() {
        @Override
        public void setActive(String callId, android.os.ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.sA");
                createTransactions(callId, callback, SET_ACTIVE);
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override

        public void answer(int videoState, String callId, android.os.ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.a");
                createTransactions(callId, callback, ANSWER, videoState);
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setInactive(String callId, android.os.ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.sI");
                createTransactions(callId, callback, SET_INACTIVE);
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void disconnect(String callId, DisconnectCause disconnectCause,
                android.os.ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.d");
                createTransactions(callId, callback, DISCONNECT, disconnectCause);
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setMuteState(boolean isMuted, android.os.ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.sMS");
                addTransactionsToManager(SET_MUTE_STATE,
                        new SetMuteStateTransaction(mCallsManager, isMuted), callback);
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void startCallStreaming(String callId, android.os.ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.sCS");
                createTransactions(callId, callback, START_STREAMING);
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void requestVideoState(int videoState, String callId, ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.rVS");
                createTransactions(callId, callback, REQUEST_VIDEO_STATE, videoState);
            } finally {
                Binder.restoreCallingIdentity(token);
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
                        mCallSequencingAdapter.setActive(call,
                                getCompleteReceiver(action, callback));
                        break;
                    case ANSWER:
                        mCallSequencingAdapter.setAnswered(call, (int) objects[0] /*VideoState*/,
                                getCompleteReceiver(action, callback));
                        break;
                    case DISCONNECT:
                        DisconnectCause dc = (DisconnectCause) objects[0];
                        mCallSequencingAdapter.setDisconnected(call, dc,
                                getCompleteReceiver(action, callback));
                        break;
                    case SET_INACTIVE:
                        mCallSequencingAdapter.setInactive(call,
                                getCompleteReceiver(action,callback));
                        break;
                    case START_STREAMING:
                        addTransactionsToManager(action,
                                mStreamingController.getStartStreamingTransaction(mCallsManager,
                                TransactionalServiceWrapper.this, call, mLock),  callback);
                        break;
                    case REQUEST_VIDEO_STATE:
                        addTransactionsToManager(action,
                                new RequestVideoStateTransaction(mCallsManager, call,
                                        (int) objects[0]), callback);
                        break;
                }
            } else {
                Bundle exceptionBundle = new Bundle();
                exceptionBundle.putParcelable(TRANSACTION_EXCEPTION_KEY,
                        new CallException(String.format("Telecom cannot process [%s] because the"
                                + " call with id=[%s] is no longer "
                                + "being tracked. This is most likely a result of the call "
                                + "already being disconnected and removed. Try re-adding the call"
                                + " via TelecomManager#addCall", action, callId),
                                CODE_CALL_IS_NOT_BEING_TRACKED));
                callback.send(CODE_CALL_IS_NOT_BEING_TRACKED, exceptionBundle);
                if (mFeatureFlags.enableCallExceptionAnomReports()) {
                    mAnomalyReporter.reportAnomaly(
                            CALL_IS_NO_LONGER_BEING_TRACKED_ERROR_UUID,
                            CALL_IS_NO_LONGER_BEING_TRACKED_ERROR_MSG);
                }
            }
        }

        @Override
        public void requestCallEndpointChange(CallEndpoint endpoint, ResultReceiver callback) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.rCEC");
                addTransactionsToManager(CALL_ENDPOINT_CHANGE,
                        new EndpointChangeTransaction(endpoint, mCallsManager), callback);
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        /**
         * Application would like to inform InCallServices of an event
         */
        @Override
        public void sendEvent(String callId, String event, Bundle extras) {
            long token = Binder.clearCallingIdentity();
            try {
                Log.startSession("TSW.sE");
                Call call = mTrackedCalls.get(callId);
                if (call != null) {
                    call.onConnectionEvent(event, extras);
                } else {
                    Log.i(TAG,
                            "sendEvent: was called but there is no call with id=[%s] cannot be "
                                    + "found. Most likely the call has been disconnected");
                }
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }
    };

    private void addTransactionsToManager(String action, CallTransaction transaction,
            ResultReceiver callback) {
        Log.d(TAG, "addTransactionsToManager");
        CompletableFuture<Boolean> transactionResult = mTransactionManager
                .addTransaction(transaction, getCompleteReceiver(action, callback));
    }

    private OutcomeReceiver<CallTransactionResult, CallException> getCompleteReceiver(
            String action, ResultReceiver callback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(CallTransactionResult result) {
                Log.d(TAG, "completeReceiver: onResult[" + action + "]:" + result);
                callback.send(TELECOM_TRANSACTION_SUCCESS, new Bundle());
            }

            @Override
            public void onError(CallException exception) {
                Log.d(TAG, "completeReceiver: onError[" + action + "]" + exception);
                Bundle extras = new Bundle();
                extras.putParcelable(TRANSACTION_EXCEPTION_KEY, exception);
                callback.send(exception == null ? CallException.CODE_ERROR_UNKNOWN :
                        exception.getCode(), extras);
            }
        };
    }

    public ICallControl getICallControl() {
        return mICallControl;
    }

    /***
     *********************************************************************************************
     **                    ICallEventCallback: Server --> Client                                **
     **********************************************************************************************
     */

    public CompletableFuture<Boolean> onSetActive(Call call) {
        CallTransaction callTransaction = new CallEventCallbackAckTransaction(
                mICallEventCallback, ON_SET_ACTIVE, call.getId(), mLock);
        CompletableFuture<Boolean> onSetActiveFuture;
        try {
            Log.startSession("TSW.oSA");
            Log.d(TAG, String.format(Locale.US, "onSetActive: callId=[%s]", call.getId()));
            onSetActiveFuture = mCallSequencingAdapter.onSetActive(call,
                    callTransaction, result ->
                            Log.i(TAG, String.format(Locale.US,
                                    "%s: onResult: callId=[%s], result=[%s]", ON_SET_ACTIVE,
                                    call.getId(), result)));
        } finally {
            Log.endSession();
        }
        return onSetActiveFuture;
    }

    public CompletableFuture<Boolean> onAnswer(Call call, int videoState) {
        CompletableFuture<Boolean> onAnswerFuture;
        try {
            Log.startSession("TSW.oA");
            Log.d(TAG, String.format(Locale.US, "onAnswer: callId=[%s]", call.getId()));
            onAnswerFuture = mCallSequencingAdapter.onSetAnswered(call, videoState,
                    new CallEventCallbackAckTransaction(mICallEventCallback,
                            ON_ANSWER, call.getId(), videoState, mLock),
                    result -> Log.i(TAG, String.format(Locale.US,
                            "%s: onResult: callId=[%s], result=[%s]",
                            ON_ANSWER, call.getId(), result)));
        } finally {
            Log.endSession();
        }
        return onAnswerFuture;
    }

    public CompletableFuture<Boolean> onSetInactive(Call call) {
        CallTransaction callTransaction = new CallEventCallbackAckTransaction(
                mICallEventCallback, ON_SET_INACTIVE, call.getId(), mLock);
        CompletableFuture<Boolean> onSetInactiveFuture;
        try {
            Log.startSession("TSW.oSI");
            Log.i(TAG, String.format(Locale.US, "onSetInactive: callId=[%s]", call.getId()));
            onSetInactiveFuture = mCallSequencingAdapter.onSetInactive(call,
                    callTransaction, new OutcomeReceiver<>() {
                        @Override
                        public void onResult(CallTransactionResult result) {
                            Log.i(TAG, String.format(Locale.US, "onSetInactive: callId=[%s]"
                                            + ", result=[%s]",
                                    call.getId(), result));
                        }

                        @Override
                        public void onError(CallException exception) {
                            Log.w(TAG, "onSetInactive: onError: e.code=[%d], e.msg=[%s]",
                                    exception.getCode(), exception.getMessage());
                        }
                    });
        } finally {
            Log.endSession();
        }
        return onSetInactiveFuture;
    }

    public CompletableFuture<Boolean> onDisconnect(Call call,
            DisconnectCause cause) {
        CallTransaction callTransaction = new CallEventCallbackAckTransaction(
                mICallEventCallback, ON_DISCONNECT, call.getId(), cause, mLock);
        CompletableFuture<Boolean> onDisconnectFuture;
        try {
            Log.startSession("TSW.oD");
            Log.d(TAG, String.format(Locale.US, "onDisconnect: callId=[%s]", call.getId()));
            onDisconnectFuture = mCallSequencingAdapter.onSetDisconnected(call, cause,
                    callTransaction,
                    result -> Log.i(TAG, String.format(Locale.US,
                            "%s: onResult: callId=[%s], result=[%s]",
                            ON_DISCONNECT, call.getId(), result)));
        } finally {
            Log.endSession();
        }
        return onDisconnectFuture;
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
                        public void onResult(CallTransactionResult result) {
                        }

                        @Override
                        public void onError(CallException exception) {
                            Log.w(TAG, "onCallStreamingStarted: onError: "
                                            + "e.code=[%d], e.msg=[%s]",
                                    exception.getCode(), exception.getMessage());
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

    @Override
    public void onCallEndpointChanged(Call call, CallEndpoint endpoint) {
        if (call != null) {
            try {
                mICallEventCallback.onCallEndpointChanged(call.getId(), endpoint);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void onAvailableCallEndpointsChanged(Call call, Set<CallEndpoint> endpoints) {
        if (call != null) {
            try {
                mICallEventCallback.onAvailableCallEndpointsChanged(call.getId(),
                        endpoints.stream().toList());
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void onMuteStateChanged(Call call, boolean isMuted) {
        if (call != null) {
            try {
                mICallEventCallback.onMuteStateChanged(call.getId(), isMuted);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void onVideoStateChanged(Call call, int videoState) {
        if (call != null) {
            try {
                mICallEventCallback.onVideoStateChanged(call.getId(), videoState);
            } catch (RemoteException e) {
            }
        }
    }

    public void removeCallFromWrappers(Call call) {
        if (call != null) {
            try {
                // remove the call from frameworks wrapper (client side)
                mICallEventCallback.removeCallFromTransactionalServiceWrapper(call.getId());
            } catch (RemoteException e) {
            }
            // remove the call from this class/wrapper (server side)
            untrackCall(call);
        }
    }

    @Override
    public void sendCallEvent(Call call, String event, Bundle extras) {
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
    private void setDeathRecipient(ICallEventCallback callEventCallback) {
        try {
            callEventCallback.asBinder().linkToDeath(mAppDeathListener, 0);
        } catch (Exception e) {
            Log.w(TAG, "setDeathRecipient: hit exception=[%s] trying to link binder to death",
                    e.toString());
        }
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

    public void stopCallStreaming(Call call) {
        Log.i(this, "stopCallStreaming; callid=%s", call.getId());
        if (call != null && call.isStreaming()) {
            CallTransaction stopStreamingTransaction = mStreamingController
                    .getStopStreamingTransaction(call, mLock);
            addTransactionsToManager(STOP_STREAMING, stopStreamingTransaction,
                    new ResultReceiver(null));
        }
    }
}
