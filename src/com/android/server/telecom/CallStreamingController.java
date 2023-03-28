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

import static android.telecom.CallStreamingService.STREAMING_FAILED_SENDER_BINDING_ERROR;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.CallException;
import android.telecom.CallStreamingService;
import android.telecom.StreamingCall;
import android.util.Log;

import com.android.internal.telecom.ICallStreamingService;
import com.android.server.telecom.voip.VoipCallTransaction;
import com.android.server.telecom.voip.VoipCallTransactionResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class CallStreamingController extends CallsManagerListenerBase {
    private Call mStreamingCall;
    private TransactionalServiceWrapper mTransactionalServiceWrapper;
    private ICallStreamingService mService;
    private final Context mContext;
    private CallStreamingServiceConnection mConnection;
    private boolean mIsStreaming;
    private final Object mLock;
    private TelecomSystem.SyncRoot mTelecomLock;

    public CallStreamingController(Context context, TelecomSystem.SyncRoot telecomLock) {
        mLock = new Object();
        mContext = context;
        mTelecomLock = telecomLock;
    }

    private void onConnectedInternal(Call call, TransactionalServiceWrapper wrapper,
            IBinder service) throws RemoteException {
        synchronized (mLock) {
            mStreamingCall = call;
            mTransactionalServiceWrapper = wrapper;
            mService = ICallStreamingService.Stub.asInterface(service);
            mService.setStreamingCallAdapter(new StreamingCallAdapter(mTransactionalServiceWrapper,
                    mStreamingCall,
                    mStreamingCall.getTargetPhoneAccount().getComponentName().getPackageName()));
            mService.onCallStreamingStarted(new StreamingCall(
                    mTransactionalServiceWrapper.getComponentName(),
                    mStreamingCall.getCallerDisplayName(),
                    mStreamingCall.getContactUri(), new Bundle()));
            mIsStreaming = true;
        }
    }

    private void resetController() {
        synchronized (mLock) {
            mStreamingCall = null;
            mTransactionalServiceWrapper = null;
            if (mConnection != null) {
                mContext.unbindService(mConnection);
                mConnection = null;
            }
            mService = null;
            mIsStreaming = false;
        }
    }

    public boolean isStreaming() {
        synchronized (mLock) {
            return mIsStreaming;
        }
    }

    public static class QueryCallStreamingTransaction extends VoipCallTransaction {
        private static final String TAG = QueryCallStreamingTransaction.class.getSimpleName();
        private final CallsManager mCallsManager;

        public QueryCallStreamingTransaction(CallsManager callsManager) {
            super(callsManager.getLock());
            mCallsManager = callsManager;
        }

        @Override
        public CompletableFuture<VoipCallTransactionResult> processTransaction(Void v) {
            Log.d(TAG, "processTransaction");
            CompletableFuture<VoipCallTransactionResult> future = new CompletableFuture<>();

            if (mCallsManager.getCallStreamingController().isStreaming()) {
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED,
                        "STREAMING_FAILED_ALREADY_STREAMING"));
            } else {
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_SUCCEED, null));
            }

            return future;
        }
    }

    public static class AudioInterceptionTransaction extends VoipCallTransaction {
        private static final String TAG = AudioInterceptionTransaction.class.getSimpleName();

        private Call mCall;
        private boolean mEnterInterception;

        public AudioInterceptionTransaction(Call call, boolean enterInterception,
                TelecomSystem.SyncRoot lock) {
            super(lock);
            mCall = call;
            mEnterInterception = enterInterception;
        }

        @Override
        public CompletableFuture<VoipCallTransactionResult> processTransaction(Void v) {
            Log.d(TAG, "processTransaction");
            CompletableFuture<VoipCallTransactionResult> future = new CompletableFuture<>();

            if (mEnterInterception) {
                mCall.startStreaming();
            } else {
                mCall.stopStreaming();
            }
            future.complete(new VoipCallTransactionResult(VoipCallTransactionResult.RESULT_SUCCEED,
                    null));
            return future;
        }
    }

    public StreamingServiceTransaction getCallStreamingServiceTransaction(Context context,
            TransactionalServiceWrapper wrapper, Call call) {
        return new StreamingServiceTransaction(context, wrapper, call);
    }

    public class StreamingServiceTransaction extends VoipCallTransaction {
        private static final String TAG = "StreamingServiceTransaction";
        public static final String MESSAGE = "STREAMING_FAILED_NO_SENDER";
        private final TransactionalServiceWrapper mWrapper;
        private final Context mContext;
        private final UserHandle mUserHandle;
        private final Call mCall;

        public StreamingServiceTransaction(Context context, TransactionalServiceWrapper wrapper,
                Call call) {
            super(mTelecomLock);
            mWrapper = wrapper;
            mCall = call;
            mUserHandle = mCall.getInitiatingUser();
            mContext = context;
        }

        @SuppressLint("LongLogTag")
        @Override
        public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
            Log.d(TAG, "processTransaction");
            CompletableFuture<VoipCallTransactionResult> future = new CompletableFuture<>();

            RoleManager roleManager = mContext.getSystemService(RoleManager.class);
            PackageManager packageManager = mContext.getPackageManager();
            if (roleManager == null || packageManager == null) {
                Log.e(TAG, "Can't find system service");
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED, MESSAGE));
                return future;
            }

            List<String> holders = roleManager.getRoleHoldersAsUser(
                    RoleManager.ROLE_SYSTEM_CALL_STREAMING, mUserHandle);
            if (holders.isEmpty()) {
                Log.e(TAG, "Can't find streaming app");
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED, MESSAGE));
                return future;
            }

            Intent serviceIntent = new Intent(CallStreamingService.SERVICE_INTERFACE);
            serviceIntent.setPackage(holders.get(0));
            List<ResolveInfo> infos = packageManager.queryIntentServicesAsUser(serviceIntent,
                    PackageManager.GET_META_DATA, mUserHandle);
            if (infos.isEmpty()) {
                Log.e(TAG, "Can't find streaming service");
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED, MESSAGE));
                return future;
            }

            ServiceInfo serviceInfo = infos.get(0).serviceInfo;

            if (serviceInfo.permission == null || !serviceInfo.permission.equals(
                    Manifest.permission.BIND_CALL_STREAMING_SERVICE)) {
                android.telecom.Log.w(TAG, "Must require BIND_CALL_STREAMING_SERVICE: " +
                        serviceInfo.packageName);
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED, MESSAGE));
                return future;
            }
            Intent intent = new Intent(CallStreamingService.SERVICE_INTERFACE);
            intent.setComponent(serviceInfo.getComponentName());

            mConnection =  new CallStreamingServiceConnection(mCall, mWrapper, future);
            if (!mContext.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE
                    | Context.BIND_FOREGROUND_SERVICE
                    | Context.BIND_SCHEDULE_LIKE_TOP_APP, mUserHandle)) {
                Log.e(TAG, "Can't bind to streaming service");
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED,
                        "STREAMING_FAILED_SENDER_BINDING_ERROR"));
            }

            return future;
        }
    }

    public UnbindStreamingServiceTransaction getUnbindStreamingServiceTransaction() {
        return new UnbindStreamingServiceTransaction();
    }

    public class UnbindStreamingServiceTransaction extends VoipCallTransaction {
        private static final String TAG = "UnbindStreamingServiceTransaction";

        public UnbindStreamingServiceTransaction() {
            super(mTelecomLock);
        }

        @SuppressLint("LongLogTag")
        @Override
        public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
            Log.d(TAG, "processTransaction");
            CompletableFuture<VoipCallTransactionResult> future = new CompletableFuture<>();

            resetController();
            future.complete(new VoipCallTransactionResult(VoipCallTransactionResult.RESULT_SUCCEED,
                    null));
            return future;
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (mStreamingCall == call) {
            mTransactionalServiceWrapper.stopCallStreaming(call);
        }
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        // TODO: make sure we are only able to stream the one call and not switch focus to another
        // and have it streamed too
        if (mStreamingCall == call && oldState != newState) {
            CallStreamingStateChangeTransaction transaction = null;
            switch (newState) {
                case CallState.ACTIVE:
                    transaction = new CallStreamingStateChangeTransaction(
                            StreamingCall.STATE_STREAMING);
                    break;
                case CallState.ON_HOLD:
                    transaction = new CallStreamingStateChangeTransaction(
                            StreamingCall.STATE_HOLDING);
                case CallState.DISCONNECTING:
                case CallState.DISCONNECTED:
                    transaction = new CallStreamingStateChangeTransaction(
                            StreamingCall.STATE_DISCONNECTED);
                default:
                    // ignore
            }
            if (transaction != null) {
                mTransactionalServiceWrapper.getTransactionManager().addTransaction(transaction,
                        new OutcomeReceiver<>() {
                            @Override
                            public void onResult(VoipCallTransactionResult result) {
                                // ignore
                            }

                            @Override
                            public void onError(CallException exception) {
                                Log.e(String.valueOf(this), "Exception when set call "
                                        + "streaming state to streaming app: " + exception);
                            }
                        });
            }
        }
    }

    private class CallStreamingStateChangeTransaction extends VoipCallTransaction {
        @StreamingCall.StreamingCallState int mState;

        public CallStreamingStateChangeTransaction(@StreamingCall.StreamingCallState int state) {
            super(mTelecomLock);
            mState = state;
        }

        @Override
        public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
            CompletableFuture<VoipCallTransactionResult> future = new CompletableFuture<>();
            try {
                mService.onCallStreamingStateChanged(mState);
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_SUCCEED, null));
            } catch (RemoteException e) {
                future.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED, "Exception when request "
                        + "setting state to streaming app."));
            }
            return future;
        }
    }

    private class CallStreamingServiceConnection implements
            ServiceConnection {
        private Call mCall;
        private TransactionalServiceWrapper mWrapper;
        private CompletableFuture<VoipCallTransactionResult> mFuture;

        public CallStreamingServiceConnection(Call call, TransactionalServiceWrapper wrapper,
                CompletableFuture<VoipCallTransactionResult> future) {
            mCall = call;
            mWrapper = wrapper;
            mFuture = future;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                onConnectedInternal(mCall, mWrapper, service);
                mFuture.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_SUCCEED, null));
            } catch (RemoteException e) {
                resetController();
                mFuture.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED,
                        StreamingServiceTransaction.MESSAGE));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearBinding();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            clearBinding();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            clearBinding();
        }

        private void clearBinding() {
            try {
                if (mService != null) {
                    mService.onCallStreamingStopped();
                }
            } catch (RemoteException e) {
                Log.w(String.valueOf(this), "Exception when stop call streaming:" + e);
            }
            resetController();
            if (!mFuture.isDone()) {
                mFuture.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_FAILED,
                        "STREAMING_FAILED_SENDER_BINDING_ERROR"));
            } else {
                mWrapper.onCallStreamingFailed(mCall, STREAMING_FAILED_SENDER_BINDING_ERROR);
            }
        }
    }
}
