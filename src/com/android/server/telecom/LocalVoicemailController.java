package com.android.server.telecom;

/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.LocalVoicemailService;
import android.telecom.Log;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;

import com.android.internal.telecom.ILocalVoicemailService;
import com.android.internal.telecom.ILocalVoicemailServiceAdapter;
import com.android.internal.util.IndentingPrintWriter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Responsible for handling local voicemail processing via an OEM {@link LocalVoicemailService}.
 */
public class LocalVoicemailController extends CallsManagerListenerBase implements
        AudioModeTracker.AudioModeListener {

    /**
     * Abstracts out dependencies on {@link CallsManager} to enable testing.
     */
    public interface CallsManagerAdapter {
        void startLocalVoicemail(Call call);

        UserHandle getCurrentUserHandle();

        void disconnectCall(Call call);
    }

    /**
     * {@link ServiceConnection} handling changes to binding of the {@link LocalVoicemailService}.
     */
    private class LocalVoicemailServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.startSession("LVMC.oSC", Log.getPackageAbbreviation(name));
            try {
                synchronized (mLock) {
                    mILocalVoicemailService = ILocalVoicemailService.Stub.asInterface(service);
                    Log.i(this, "onServiceConnected: connected");
                    handleServiceConnected(mILocalVoicemailService);
                }
                Log.i(LocalVoicemailController.this, "onServiceConnected: cmp=%s", name);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.startSession("LVMC.oSD", Log.getPackageAbbreviation(name));
            try {
                synchronized (mLock) {
                    // The local voicemail service should have disconnected the call using
                    // LocalVoicemailService#disconnectCall, but in case it just unbound, we'll
                    // disconnect and cleanup here.
                    maybeDisconnectCall();
                    maybeUnbindLocalVoicemailService();
                    mILocalVoicemailService = null;
                    mConnection = null;
                }
                Log.i(LocalVoicemailController.this, "onServiceDisconnected: cmp=%s", name);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.startSession("LVMC.oBD", Log.getPackageAbbreviation(name));
            try {
                synchronized (mLock) {
                    stopLocalVoicemail();
                    mILocalVoicemailService = null;
                    mConnection = null;
                }
                Log.w(LocalVoicemailController.this, "onBindingDied: cmp=%s", name);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.startSession("LVMC.oNB", Log.getPackageAbbreviation(name));
            try {
                synchronized (mLock) {
                    stopLocalVoicemail();
                }
            } finally {
                Log.endSession();
            }
        }
    }

    /**
     * Handles incoming requests from the {@link LocalVoicemailService}.
     */
    public class LocalVoicemailServiceAdapter extends ILocalVoicemailServiceAdapter.Stub {
        private final String mOwnerPackageName;
        private final String mOwnerPackageNameAbbreviation;

        public LocalVoicemailServiceAdapter(@NonNull String ownerPackageName) {
            mOwnerPackageName = ownerPackageName;
            mOwnerPackageNameAbbreviation = Log.getPackageAbbreviation(ownerPackageName);
        }

        @Override
        public void disconnectCall(String callId) throws RemoteException {
            try {
                Log.startSession("LVMSA.dC", mOwnerPackageNameAbbreviation);
                long token = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Log.i(this, "disconnectCall; callId=%s", callId);
                        stopLocalVoicemail();
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } finally {
                Log.endSession();
            }
        }
    }

    private final LocalLog mLocalLog = new LocalLog(10);
    // The OEM's defined local voicemail service package
    private final String mPackageName;
    private final Context mContext;
    private final CallsManagerAdapter mCallsManagerAdapter;
    private final ScheduledExecutorService mScheduledExecutorService;
    private final TelecomSystem.SyncRoot mLock;
    private final Map<Call, ScheduledFuture<?>> mScheduledFutureMap = new ArrayMap<>(2);
    // Tracks all calls so we can correctness-check to see if we have to terminate local voicemail
    // because of call concurrency.
    private final Set<Call> mCalls = new ArraySet();
    /**
     * This call is pending local voicemail processing; this is set as soon as we set the timeout
     * for local voicemail.
     */
    private Call mCall;
    private ILocalVoicemailService mILocalVoicemailService;
    private LocalVoicemailServiceAdapter mAdapter;
    private LocalVoicemailServiceConnection mConnection;

    public LocalVoicemailController(CallsManagerAdapter adapter, Context context,
            ScheduledExecutorService scheduledExecutorService, TelecomSystem.SyncRoot lock) {
        //TODO(b/394367444): add package name lookup from config.xml
        mPackageName = "com.android.server.telecom.testapps.localvoicemail";
        mCallsManagerAdapter = adapter;
        mContext = context;
        mScheduledExecutorService = scheduledExecutorService;
        mLock = lock;
    }

    /**
     * Handles audio mode changes reported by the {@link AudioModeTracker}.
     * We are listening to {@link AudioManager} rather than to {@link CallAudioModeStateMachine}
     * because requests to {@link AudioManager} are async and it could take some time for the audio
     * mode change to complete which would cause {@link LocalVoicemailService} to fail to get the
     * uplink and downlink audio resources.
     *
     * @param audioMode The new audio mode. See {@link AudioManager.AudioMode}.
     */
    @Override
    public void onAudioModeChanged(int audioMode) {
        if (audioMode == AudioManager.MODE_CALL_REDIRECT && mCall != null) {
            // We have a pending local voicemail call and the mode just changed to call redirect, so
            // start the local voicemail service.
            Log.i(this, "handleAudioModeChange: %s - starting local voicemail",
                    AudioModeTracker.audioModeToString(audioMode));
            maybeBindLocalVoicemailService();
        } else if (mCall != null && mConnection != null) {
            // If the audio mode changes to something other than `MODE_CALL_REDIRECT` while we are
            // in local voicemail mode, we need to stop local voicemail immediately.
            Log.i(this, "handleAudioModeChange: %s - stopping voicemail for call %s",
                    AudioModeTracker.audioModeToString(audioMode), mCall.getId());
            maybeUnbindLocalVoicemailService();
        }
    }

    /**
     * Listens to new calls added to Telecom for potentially performing local voicemail processing.
     */
    @Override
    public void onCallAdded(Call call) {
        if (call.isExternalCall()) {
            // External calls don't impact local vm
            return;
        }
        if (mCalls.isEmpty()) {
            // Only start local voicemail timeout if there are no other calls.
            maybeStartLocalVoicemailTimeout(call, call.getState());
        }
        if (mCalls.add(call)) {
            performLocalVoicemailCorrectnessCheck();
        }
    }

    @Override
    public void onExternalCallChanged(Call call, boolean isExternalCall) {
        if (!isExternalCall && mCalls.add(call)) {
            // Previously external call became non-external, so we are tracking it now.
            performLocalVoicemailCorrectnessCheck();
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        mCalls.remove(call);
        maybeCleanupCall(call);
    }

    /**
     * Handles changes to call states:
     * 1. If the call moves to a ringing state, potentially starts the local voicemail timeout so
     * that local voicemail processing can happen for the call.
     * 2. If the call moves to state {@link CallState#LOCAL_VOICEMAIL}, starts the local voicemail
     * binding.
     *
     * @param call     The call.
     * @param oldState The previous call state.
     * @param newState The new call state.
     */
    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (newState == CallState.RINGING) {
            maybeStartLocalVoicemailTimeout(call, newState);
        } else if (newState == CallState.LOCAL_VOICEMAIL) {
            Log.i(this, "onCallStateChanged: call %s is local voicemail", mCall.getId());
        }
    }

    /**
     * Determines the active local voicemail service, taking into account the test override.
     *
     * @return The package name of the active local voicemail service.
     */
    private @Nullable String getActiveLocalVoicemailService() {
        //TODO(b/394367444) add test override via shell command
        /*if (mTestPackageName != null) {
            return mTestPackageName;
        }*/

        return mPackageName;
    }

    /**
     * Check for a situation where another call was added which would necessitate stopping local
     * voicemail.  In practice this should NOT happen as call sequencing is going to ensure that
     * calls are not in a state which would result in local voicemail happening when there are other
     * calls.
     */
    private void performLocalVoicemailCorrectnessCheck() {
        if (mCall != null && mCalls.contains(mCall) && mCalls.size() > 1) {
            // We are in a state where local voicemail is processing but there is another call
            // present; we will terminate local voicemail.
            Log.i(this, "performLocalVoicemailCorrectnessCheck: multiple calls are present - "
                    + "stopping local voicemail");
            stopLocalVoicemail();
        }
    }

    /**
     * Given a call, will potentially start the local voicemail timeout for that call.  This is how
     * we let the call "ring out" to local voicemail.
     *
     * @param call  The call.
     * @param state The call's state.
     */
    private void maybeStartLocalVoicemailTimeout(final Call call, int state) {
        if (call.isSelfManaged() || call.isExternalCall() || mCall != null) {
            return;
        }
        //TODO(b/394367444) handle different calls; there can only be one local voicemail call.
        if (state == CallState.RINGING) {
            Log.i(this, "maybeStartLocalVoicemailTimeout: scheduling voicemail for call %s",
                    call.getId());
            //TODO(b/394367444) add Telecom API to set/get the timeout; this also requires carrier
            //configuration for the range that a carrier CAN support.
            ScheduledFuture<?> timeoutFuture = mScheduledExecutorService.schedule(
                    getAnswerRunnable(call), 10, TimeUnit.SECONDS);
            mScheduledFutureMap.put(call, timeoutFuture);
            mCall = call;
        }
    }

    /**
     * Get a runnable we can schedule that will answer a call for local voicemail.
     *
     * @param call the call.
     * @return the runnable.
     */
    private Runnable getAnswerRunnable(final Call call) {
        Runnable answerRunnable = new android.telecom.Logging.Runnable("LVMC.aR", mLock) {
            @Override
            public void loggedRun() {
                answerForLocalVoicemail(call);
            }
        }.prepare();
        return answerRunnable;
    }

    /**
     * Initiate answering a call for local voicemail.
     *
     * @param call the call
     */
    private void answerForLocalVoicemail(Call call) {
        Log.i(this, "answerForLocalVoicemail: answering call for voicemail, call=%s", call.getId());
        mCallsManagerAdapter.startLocalVoicemail(call);
    }

    /**
     * Cleanup a call that was scheduled for local voicemail.
     *
     * @param call the call.
     */
    private void maybeCleanupCall(Call call) {
        if (mScheduledFutureMap.containsKey(call)) {
            ScheduledFuture<?> existingTimeout = mScheduledFutureMap.remove(call);
            existingTimeout.cancel(false /* cancelIfRunning */);
        }
        if (mCall == call) {
            mCall = null;
        }
    }

    /**
     * Attempts to bind to the {@link LocalVoicemailService}.
     * NOTE: DO NOT do anything else in here other than just binding.  This is called from an async
     * task executor outside the Telecom lock.
     *
     * @return {@code true} if the binding was successful, {@code false} otherwise.
     */
    private boolean maybeBindLocalVoicemailService() {
        if (mConnection != null) {
            Log.i(this, "maybeBindLocalVoicemailService: already bound");
            return false;
        }

        mConnection = new LocalVoicemailServiceConnection();
        boolean bound = bindLocalVoicemailService(getActiveLocalVoicemailService(),
                mCallsManagerAdapter.getCurrentUserHandle());
        Log.i(this, "maybeBindLocalVoicemailService: bound=%s", bound ? "true" : "false");
        if (!bound) {
            mConnection = null;
        }
        return bound;
    }

    /**
     * Initiates binding to a {@link LocalVoicemailService}.
     */
    private boolean bindLocalVoicemailService(String packageName, UserHandle userHandle) {
        Intent intent = new Intent(LocalVoicemailService.SERVICE_INTERFACE);
        intent.setPackage(mPackageName);

        Context userContext = mContext.createContextAsUser(userHandle, 0);
        List<ResolveInfo> entries = userContext.getPackageManager().queryIntentServices(intent, 0);
        if (entries.isEmpty()) {
            Log.i(this, "bindLocalVoicemailService: %s has no service.", packageName);
            return false;
        }

        ResolveInfo entry = entries.get(0);
        if (entry.serviceInfo == null) {
            Log.i(this, "bindLocalVoicemailService: %s has no service info.", packageName);
            return false;
        }

        // TODO(b/394367444): add permission check.
        /*
        if (entry.serviceInfo.permission == null || !entry.serviceInfo.permission.equals(
                Manifest.permission.BIND_LOCAL_VOICEMAIL_SERVICE)) {
            Log.i(this, "bindLocalVoicemailService: %s doesn't require "
                    + "BIND_LOCAL_VOICEMAIL_SERVICE.", packageName);
            return false;
        }*/
        ComponentName componentName =
                new ComponentName(entry.serviceInfo.packageName, entry.serviceInfo.name);
        intent.setComponent(componentName);
        if (mContext.bindServiceAsUser(
                intent,
                mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                userHandle)) {
            Log.i(this, "bindLocalVoicemailService, found service, waiting for it to connect");
            mLocalLog.log("bound to " + componentName);
            return true;
        }

        // Binding failed.
        return false;
    }

    /**
     * Handles successful binding to the {@link LocalVoicemailService} by setting the adapter for
     * communication back from the service and then telling the service to start local voicemail for
     * a call.
     */
    private void handleServiceConnected(ILocalVoicemailService service) {
        mAdapter = new LocalVoicemailServiceAdapter(getActiveLocalVoicemailService());
        try {
            Log.i(this, "handleServiceConnected: handleServiceConnected");
            // Add adapter for communication back from the local voicemail service to Telecom.
            service.setAdapter(mAdapter);
            Log.i(this, "handleServiceConnected: adapter set");
            service.startLocalVoicemail(
                    ParcelableCallUtils.toParcelableCallForScreening(mCall, false, true));
            Log.i(this, "handleServiceConnected: started");
        } catch (RemoteException e) {
            Log.w(this, "handleServiceConnected: error=%s", e);
        }
    }

    /**
     * Handles unbinding from the {@link LocalVoicemailService} if bound; if not bound does nothing.
     */
    private void maybeUnbindLocalVoicemailService() {
        if (mConnection != null) {
            Log.i(this, "maybeUnbindLocalVoicemailService - unbinding from %s",
                    getActiveLocalVoicemailService());
            try {
                mLocalLog.log("unbound");
                mContext.unbindService(mConnection);
                mILocalVoicemailService = null;
                mConnection = null;
            } catch (IllegalArgumentException e) {
                Log.e(this, e, "maybeUnbindLocalVoicemailService: Exception when unbind %s",
                        getActiveLocalVoicemailService());
            }
        } else {
            Log.w(this, "maybeUnbindLocalVoicemailService - already unbound");
        }
    }

    /**
     * If there is a local voicemail call, disconnect it and clean it up.
     */
    private void maybeDisconnectCall() {
        if (mCall != null) {
            mCallsManagerAdapter.disconnectCall(mCall);
            maybeCleanupCall(mCall);
        }
    }

    /**
     * Stops local voicemail processing by disconnecting the local voicemail call and unbinding from
     * the local voicemail service.
     */
    private void stopLocalVoicemail() {
        if (mCall == null) {
            // Not in local voicemail.
            return;
        }
        maybeDisconnectCall();
        maybeUnbindLocalVoicemailService();
    }

    /**
     * Dumps the state of the {@link LocalVoicemailController}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.print("Current call: ");
        if (mCall != null) {
            pw.println(mCall.getId());
        } else {
            pw.println("<none>");
        }
        pw.println("Service pkg: " + mPackageName);
        pw.println("Bound: " + (mConnection == null ? "N" : "Y"));
        pw.println("Local voicemail History:");
        pw.increaseIndent();
        mLocalLog.dump(pw);
        pw.decreaseIndent();
    }
}
