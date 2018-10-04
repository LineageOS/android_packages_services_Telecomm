/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.telecom.callredirection;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.CallRedirectionService;
import android.telecom.Log;
import android.telecom.Logging.Runnable;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierConfigManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telecom.ICallRedirectionAdapter;
import com.android.internal.telecom.ICallRedirectionService;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.LogUtils;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;

import java.util.List;

/**
 * A single instance of call redirection processor that handles the call redirection with
 * user-defined {@link CallRedirectionService} and carrier {@link CallRedirectionService} for a
 * single call.
 *
 * A user-defined call redirection will be performed firstly and a carrier call redirection will be
 * performed after that; there will be a total of two call redirection cycles.
 *
 * A call redirection cycle is a cycle:
 * 1) Telecom requests a call redirection of a call with a specific {@link CallRedirectionService},
 * 2) Telecom receives the response either from a specific {@link CallRedirectionService} or from
 * the timeout.
 *
 * Telecom should return to {@link CallsManager} at the end of current call redirection
 * cycle, if
 * 1) {@link CallRedirectionService} sends {@link CallRedirectionService#cancelCall()} response
 * before timeout;
 * or 2) Telecom finishes call redirection with carrier {@link CallRedirectionService}.
 */
public class CallRedirectionProcessor implements CallRedirectionCallback {

    private class CallRedirectionAttempt {
        private final ComponentName mComponentName;
        private final String mServiceType;
        private ServiceConnection mConnection;
        private ICallRedirectionService mService;

        private CallRedirectionAttempt(ComponentName componentName, String serviceType) {
            mComponentName = componentName;
            mServiceType = serviceType;
        }

        private void process() {
            Intent intent = new Intent(CallRedirectionService.SERVICE_INTERFACE)
                    .setComponent(mComponentName);
            ServiceConnection connection = new CallRedirectionServiceConnection();
            if (mContext.bindServiceAsUser(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                    UserHandle.CURRENT)) {
                Log.d(this, "bindService, found " + mServiceType + " call redirection service,"
                        + " waiting for it to connect");
                mConnection = connection;
            }
        }

        private void onServiceBound(ICallRedirectionService service) {
            mService = service;
            try {
                mService.placeCall(new CallRedirectionAdapter(), mHandle, mPhoneAccountHandle);
                Log.addEvent(mCall, mServiceType.equals(SERVICE_TYPE_USER_DEFINED)
                        ? LogUtils.Events.REDIRECTION_SENT_USER
                        : LogUtils.Events.REDIRECTION_SENT_CARRIER);
                Log.d(this, "Requested placeCall with [handle]" + Log.pii(mHandle)
                        + " [phoneAccountHandle]" + mPhoneAccountHandle);
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to request with the found " + mServiceType + " call"
                        + " redirection service");
                finishCallRedirection();
            }
        }

        private void finishCallRedirection() {
            if (((mServiceType.equals(SERVICE_TYPE_CARRIER)) && mIsCarrierRedirectionPending)
                || ((mServiceType.equals(SERVICE_TYPE_USER_DEFINED))
                    && mIsUserDefinedRedirectionPending)) {
                if (mConnection != null) {
                    // We still need to call unbind even if the service disconnected.
                    mContext.unbindService(mConnection);
                    mConnection = null;
                }
                mService = null;
                onCallRedirectionComplete(mCall);
            }
        }

        private class CallRedirectionServiceConnection implements ServiceConnection {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                Log.startSession("CRSC.oSC");
                try {
                    synchronized (mTelecomLock) {
                        Log.addEvent(mCall, mServiceType.equals(SERVICE_TYPE_USER_DEFINED)
                                ? LogUtils.Events.REDIRECTION_BOUND_USER
                                : LogUtils.Events.REDIRECTION_BOUND_CARRIER, componentName);
                        onServiceBound(ICallRedirectionService.Stub.asInterface(service));
                    }
                } finally {
                    Log.endSession();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Log.startSession("CRSC.oSD");
                try {
                    synchronized (mTelecomLock) {
                        finishCallRedirection();
                    }
                } finally {
                    Log.endSession();
                }
            }
        }

        private class CallRedirectionAdapter extends ICallRedirectionAdapter.Stub {
            @Override
            public void cancelCall() {
                Log.startSession("CRA.cC");
                long token = Binder.clearCallingIdentity();
                try {
                    synchronized (mTelecomLock) {
                        Log.d(this, "Received cancelCall from " +  mServiceType + " call"
                                + " redirection service");
                        mShouldCancelCall = true;
                        finishCallRedirection();
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }

            @Override
            public void placeCallUnmodified() {
                Log.startSession("CRA.pCU");
                long token = Binder.clearCallingIdentity();
                try {
                    synchronized (mTelecomLock) {
                        Log.d(this, "Received placeCallUnmodified from " +  mServiceType + " call"
                                + " redirection service");
                        finishCallRedirection();
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }

            @Override
            public void redirectCall(Uri handle, PhoneAccountHandle targetPhoneAccount) {
                Log.startSession("CRA.rC");
                long token = Binder.clearCallingIdentity();
                try {
                    synchronized (mTelecomLock) {
                        mHandle = handle;
                        mPhoneAccountHandle = targetPhoneAccount;
                        Log.d(this, "Received redirectCall with [handle]" + Log.pii(mHandle)
                                + " [phoneAccountHandle]" + mPhoneAccountHandle + " from "
                                + mServiceType + " call" + " redirection service");
                        finishCallRedirection();
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }
    }

    private final Context mContext;
    private final CallsManager mCallsManager;
    private final Call mCall;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final Timeouts.Adapter mTimeoutsAdapter;
    private final TelecomSystem.SyncRoot mTelecomLock;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private CallRedirectionAttempt mAttempt;
    public static final String SERVICE_TYPE_CARRIER = "carrier";
    public static final String SERVICE_TYPE_USER_DEFINED = "user_defined";

    private PhoneAccountHandle mPhoneAccountHandle;
    private Uri mHandle;

    /**
     * Indicates if Telecom should cancel the call when the whole call redirection finishes.
     */
    private boolean mShouldCancelCall = false;
    /**
     * Indicates if Telecom is waiting for a callback from a user-defined
     * {@link CallRedirectionService}.
     */
    private boolean mIsUserDefinedRedirectionPending = false;
    /**
     * Indicates if Telecom is waiting for a callback from a carrier
     * {@link CallRedirectionService}.
     */
    private boolean mIsCarrierRedirectionPending = false;

    public CallRedirectionProcessor(
            Context context,
            CallsManager callsManager,
            Call call,
            PhoneAccountRegistrar phoneAccountRegistrar,
            Uri handle,
            PhoneAccountHandle phoneAccountHandle,
            Timeouts.Adapter timeoutsAdapter,
            TelecomSystem.SyncRoot lock) {
        mContext = context;
        mCallsManager = callsManager;
        mCall = call;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mHandle = handle;
        mPhoneAccountHandle = phoneAccountHandle;
        mTimeoutsAdapter = timeoutsAdapter;
        mTelecomLock = lock;
    }

    @Override
    public void onCallRedirectionComplete(Call call) {
        // synchronized on mTelecomLock to enter into Telecom.
        mHandler.post(new Runnable("CRP.oCRC", mTelecomLock) {
            @Override
            public void loggedRun() {
                if (mIsUserDefinedRedirectionPending) {
                    Log.addEvent(mCall, LogUtils.Events.REDIRECTION_COMPLETED_USER);
                    mIsUserDefinedRedirectionPending = false;
                    if (mShouldCancelCall) {
                        // TODO mCallsManager.onCallRedirectionComplete
                    } else {
                        performCarrierCallRedirection();
                    }
                }
                if (mIsCarrierRedirectionPending) {
                    Log.addEvent(mCall, LogUtils.Events.REDIRECTION_COMPLETED_CARRIER);
                    mIsCarrierRedirectionPending = false;
                    // TODO mCallsManager.onCallRedirectionComplete
                }
            }
        }.prepare());
    }

    /*
     * The entry to perform call redirection of the call from (@link CallsManager)
     */
    public void performCallRedirection() {
        performUserDefinedCallRedirection();
    }

    private void performUserDefinedCallRedirection() {
        Log.d(this, "performUserDefinedCallRedirection");
        ComponentName componentName = getUserDefinedCallRedirectionService(mContext);
        if (componentName != null && canBindToCallRedirectionService(mContext, componentName)) {
            mAttempt = new CallRedirectionAttempt(componentName, SERVICE_TYPE_USER_DEFINED);
            mAttempt.process();
            mIsUserDefinedRedirectionPending = true;
            processTimeoutForCallRedirection(SERVICE_TYPE_USER_DEFINED);
        } else {
            Log.i(this, "There are no user-defined call redirection services installed on this"
                    + " device.");
            performCarrierCallRedirection();
        }
    }

    private void performCarrierCallRedirection() {
        Log.d(this, "performCarrierCallRedirection");
        ComponentName componentName = getCarrierCallRedirectionService(
            mContext, mPhoneAccountHandle);
        if (componentName != null && canBindToCallRedirectionService(mContext, componentName)) {
            mAttempt = new CallRedirectionAttempt(componentName, SERVICE_TYPE_CARRIER);
            mAttempt.process();
            mIsCarrierRedirectionPending = true;
            processTimeoutForCallRedirection(SERVICE_TYPE_CARRIER);
        } else {
            Log.i(this, "There are no carrier call redirection services installed on this"
                    + " device.");
            // TODO return to CallsManager.onCallRedirectionComplete
        }
    }

    private void processTimeoutForCallRedirection(String serviceType) {
        long timeout = serviceType.equals(SERVICE_TYPE_USER_DEFINED) ?
            mTimeoutsAdapter.getUserDefinedCallRedirectionTimeoutMillis(
                mContext.getContentResolver()) : mTimeoutsAdapter
            .getCarrierCallRedirectionTimeoutMillis(mContext.getContentResolver());

        mHandler.postDelayed(new Runnable("CRP.pTFCR", null) {
            @Override
            public void loggedRun() {
                boolean isCurrentRedirectionPending =
                        serviceType.equals(SERVICE_TYPE_USER_DEFINED) ?
                                mIsUserDefinedRedirectionPending : mIsCarrierRedirectionPending;
                if (isCurrentRedirectionPending) {
                    Log.i(CallRedirectionProcessor.this,
                            serviceType + "call redirection has timed out.");
                    Log.addEvent(mCall, serviceType.equals(SERVICE_TYPE_USER_DEFINED)
                            ? LogUtils.Events.REDIRECTION_TIMED_OUT_USER
                            : LogUtils.Events.REDIRECTION_TIMED_OUT_CARRIER);
                    onCallRedirectionComplete(mCall);
                }
            }
        }.prepare(), timeout);
    }

    private ComponentName getUserDefinedCallRedirectionService(Context context) {
        // TODO get service component name from settings default value:
        // android.provider.Settings#CALL_REDIRECTION_DEFAULT_APPLICATION
        return null;
    }

    private ComponentName getCarrierCallRedirectionService(Context context, PhoneAccountHandle
            targetPhoneAccountHandle) {
        CarrierConfigManager configManager = (CarrierConfigManager)
                context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            Log.i(this, "Cannot get CarrierConfigManager.");
            return null;
        }
        PersistableBundle pb = configManager.getConfigForSubId(mPhoneAccountRegistrar
                .getSubscriptionIdForPhoneAccount(targetPhoneAccountHandle));
        if (pb == null) {
            Log.i(this, "Cannot get PersistableBundle.");
            return null;
        }
        String componentNameString = pb.getString(
            CarrierConfigManager.KEY_CALL_REDIRECTION_SERVICE_COMPONENT_NAME_STRING);
        return new ComponentName(context, componentNameString);
    }

    private boolean canBindToCallRedirectionService(Context context, ComponentName componentName) {
        Intent intent = new Intent(CallRedirectionService.SERVICE_INTERFACE);
        intent.setComponent(componentName);
        List<ResolveInfo> entries = mContext.getPackageManager().queryIntentServicesAsUser(
                intent, 0, mCallsManager.getCurrentUserHandle().getIdentifier());
        if (entries.isEmpty()) {
            Log.i(this, "There are no call redirection services installed on this device.");
            return false;
        } else if (entries.size() != 1) {
            Log.i(this, "There are multiple call redirection services installed on this device.");
            return false;
        } else {
            ResolveInfo entry = entries.get(0);
            if (entry.serviceInfo.permission == null || !entry.serviceInfo.permission.equals(
                    Manifest.permission.BIND_CALL_REDIRECTION_SERVICE)) {
                Log.w(this, "CallRedirectionService must require BIND_CALL_REDIRECTION_SERVICE"
                        + " permission: " + entry.serviceInfo.packageName);
                return false;
            }
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(
                Context.APP_OPS_SERVICE);
            if (appOps.noteOp(AppOpsManager.OP_PROCESS_OUTGOING_CALLS, Binder.getCallingUid(),
                    entry.serviceInfo.packageName) != AppOpsManager.MODE_ALLOWED) {
                Log.w(this, "App Ops does not allow " + entry.serviceInfo.packageName);
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the handler for testing purposes.
     */
    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }
}
