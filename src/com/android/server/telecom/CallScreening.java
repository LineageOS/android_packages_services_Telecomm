/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.DefaultDialerManager;
import android.telecom.CallScreeningService;
import android.text.TextUtils;

import com.android.internal.telecom.ICallScreeningAdapter;
import com.android.internal.telecom.ICallScreeningService;

import java.util.List;

/**
 * Binds to {@link ICallScreeningService} to allow call blocking. A single instance of this class
 * handles a single call.
 */
public class CallScreening {
    public interface Listener {
        void onCallScreeningCompleted(
                Call call,
                boolean shouldAllowCall,
                boolean shouldReject,
                boolean shouldAddToCallLog,
                boolean shouldShowNotification);
    }

    private final Context mContext;
    private final Listener mListener;
    private final TelecomSystem.SyncRoot mLock;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final CallsManager mCallsManager;
    private final Handler mHandler = new Handler();
    private Call mCall;
    private ICallScreeningService mService;
    private ServiceConnection mConnection;

    public CallScreening(
            Context context,
            CallsManager callsManager,
            TelecomSystem.SyncRoot lock,
            PhoneAccountRegistrar phoneAccountRegistrar,
            Call call) {
        mContext = context;
        mCallsManager = callsManager;
        mListener = callsManager;
        mLock = lock;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mCall = call;
    }

    public void screenCall() {
        if (!bindService()) {
            Log.d(this, "no service, giving up");
            performCleanup();
        } else {
            mHandler.postDelayed(new Runnable("CS.sC") {
                @Override
                public void loggedRun() {
                    synchronized (mLock) {
                        Log.event(mCall, Log.Events.SCREENING_TIMED_OUT);
                        performCleanup();
                    }
                }
            }.prepare(), Timeouts.getCallScreeningTimeoutMillis(mContext.getContentResolver()));
        }
    }

    private void performCleanup() {
        if (mCall != null) {
            mListener.onCallScreeningCompleted(mCall, true, false, false, false);
            mCall = null;
        }
        if (mConnection != null) {
            // We still need to call unbind even if the service disconnected.
            mContext.unbindService(mConnection);
            mConnection = null;
        }
        mHandler.removeCallbacksAndMessages(null);
        mService = null;
    }

    private boolean bindService() {
        String dialerPackage = DefaultDialerManager
                .getDefaultDialerApplication(mContext, UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(dialerPackage)) {
            return false;
        }

        Intent intent = new Intent(CallScreeningService.SERVICE_INTERFACE)
            .setPackage(dialerPackage);
        List<ResolveInfo> entries = mContext.getPackageManager().queryIntentServicesAsUser(
                intent, 0, mCallsManager.getCurrentUserHandle().getIdentifier());
        if (entries.isEmpty()) {
            return false;
        }

        ResolveInfo entry = entries.get(0);
        if (entry.serviceInfo == null) {
            return false;
        }

        if (entry.serviceInfo.permission == null || !entry.serviceInfo.permission.equals(
                Manifest.permission.BIND_SCREENING_SERVICE)) {
            Log.w(this, "CallScreeningService must require BIND_SCREENING_SERVICE permission: " +
                    entry.serviceInfo.packageName);
            return false;
        }

        ComponentName componentName =
                new ComponentName(entry.serviceInfo.packageName, entry.serviceInfo.name);
        Log.event(mCall, Log.Events.BIND_SCREENING, componentName);
        intent.setComponent(componentName);
        ServiceConnection connection = new CallScreeningServiceConnection();
        if (mContext.bindServiceAsUser(
                intent,
                connection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                UserHandle.CURRENT)) {
            Log.d(this, "bindService, found service, waiting for it to connect");
            mConnection = connection;
            return true;
        }

        return false;
    }

    private void onServiceBound(ICallScreeningService service) {
        mService = service;
        try {
            mService.screenCall(new CallScreeningAdapter(), ParcelableCallUtils.toParcelableCall(
                    mCall, false /* includeVideoProvider */, mPhoneAccountRegistrar));
        } catch (RemoteException e) {
            Log.e(this, e, "Failed to set the call screening adapter.");
            performCleanup();
        }
    }

    private class CallScreeningServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.startSession("CSCR.oSC");
            try {
                synchronized (mLock) {
                    Log.event(mCall, Log.Events.SCREENING_BOUND, componentName);
                    if (mCall == null) {
                        performCleanup();
                    } else {
                        onServiceBound(ICallScreeningService.Stub.asInterface(service));
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.startSession("CSCR.oSD");
            try {
                synchronized (mLock) {
                    performCleanup();
                }
            } finally {
                Log.endSession();
            }
        }
    }

    private class CallScreeningAdapter extends ICallScreeningAdapter.Stub {
        @Override
        public void allowCall(String callId) {
            try {
                Log.startSession("CSCR.aC");
                long token = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Log.d(this, "allowCall(%s)", callId);
                        if (mCall != null && mCall.getId().equals(callId)) {
                            mListener.onCallScreeningCompleted(
                                    mCall,
                                    true /* shouldAllowCall */,
                                    false /* shouldReject */,
                                    false /* shouldAddToCallLog */,
                                    false /* shouldShowNotification */);
                        } else {
                            Log.w(this, "allowCall, unknown call id: %s", callId);
                        }
                        mCall = null;
                        performCleanup();
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void disallowCall(
                String callId,
                boolean shouldReject,
                boolean shouldAddToCallLog,
                boolean shouldShowNotification) {
            try {
                Log.startSession("CSCR.dC");
                long token = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Log.i(this, "disallowCall(%s), shouldReject: %b, shouldAddToCallLog: %b, "
                                + "shouldShowNotification: %b", callId, shouldReject,
                                shouldAddToCallLog, shouldShowNotification);
                        if (mCall != null && mCall.getId().equals(callId)) {
                            mListener.onCallScreeningCompleted(
                                    mCall,
                                    false /* shouldAllowCall */,
                                    shouldReject,
                                    shouldAddToCallLog,
                                    shouldShowNotification);
                        } else {
                            Log.w(this, "disallowCall, unknown call id: %s", callId);
                        }
                        mCall = null;
                        performCleanup();
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } finally {
                Log.endSession();
            }
        }
    }
}
