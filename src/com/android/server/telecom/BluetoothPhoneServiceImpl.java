/*
 * Copyright (c) 2014, 2015 The Linux Foundation. All rights reserved.
 * Not a contribution.
 *
 * Copyright (C) 2014 The Android Open Source Project
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecom.Connection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.os.UserHandle;

import android.telecom.TelecomManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.CallsManager.CallsManagerListener;

import java.lang.NumberFormatException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codeaurora.btmultisim.IBluetoothDsdaService;
import android.content.ServiceConnection;
import android.os.RemoteException;
import android.content.ComponentName;

/**
 * Bluetooth headset manager for Telecom. This class shares the call state with the bluetooth device
 * and accepts call-related commands to perform on behalf of the BT device.
 */
public class BluetoothPhoneServiceImpl {

    public interface BluetoothPhoneServiceImplFactory {
        BluetoothPhoneServiceImpl makeBluetoothPhoneServiceImpl(Context context,
                TelecomSystem.SyncRoot lock, CallsManager callsManager,
                PhoneAccountRegistrar phoneAccountRegistrar);
    }

    private TelecomManager mTelecomManager = null;
    private IBluetoothDsdaService mBluetoothDsda = null; //Handles DSDA Service.

    private static final String TAG = "BluetoothPhoneService";

    // match up with bthf_call_state_t of bt_hf.h
    private static final int CALL_STATE_ACTIVE = 0;
    private static final int CALL_STATE_HELD = 1;
    private static final int CALL_STATE_DIALING = 2;
    private static final int CALL_STATE_ALERTING = 3;
    private static final int CALL_STATE_INCOMING = 4;
    private static final int CALL_STATE_WAITING = 5;
    private static final int CALL_STATE_IDLE = 6;

    // match up with bthf_call_state_t of bt_hf.h
    // Terminate all held or set UDUB("busy") to a waiting call
    private static final int CHLD_TYPE_RELEASEHELD = 0;
    // Terminate all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD = 1;
    // Hold all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_HOLDACTIVE_ACCEPTHELD = 2;
    // Add all held calls to a conference
    private static final int CHLD_TYPE_ADDHELDTOCONF = 3;

    private int mNumActiveCalls = 0;
    private int mNumHeldCalls = 0;
    private int mBluetoothCallState = CALL_STATE_IDLE;
    private String mRingingAddress = null;
    private int mRingingAddressType = 0;
    private Call mOldHeldCall = null;
    private static final int INVALID_SUBID = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private static final int[] LIVE_CALL_STATES =
            {CallState.CONNECTING, CallState.DIALING, CallState.ACTIVE};

    /**
     * Binder implementation of IBluetoothHeadsetPhone. Implements the command interface that the
     * bluetooth headset code uses to control call.
     */
    @VisibleForTesting
    public final IBluetoothHeadsetPhone.Stub mBinder = new IBluetoothHeadsetPhone.Stub() {
        @Override
        public boolean answerCall() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.aC");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "BT - answering call");
                    Call call = mCallsManager.getRingingCall();
                    if (call != null) {
                        mCallsManager.answerCall(call, VideoProfile.STATE_AUDIO_ONLY);
                        return true;
                    }
                    return false;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }

            }
        }

        @Override
        public boolean hangupCall() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.hC");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "BT - hanging up call");
                    Call call = mCallsManager.getForegroundCall();
                    if (call != null) {
                        mCallsManager.disconnectCall(call);
                        return true;
                    }
                    return false;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public boolean sendDtmf(int dtmf) throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.sD");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "BT - sendDtmf %c", Log.DEBUG ? dtmf : '.');
                    Call call = mCallsManager.getForegroundCall();
                    if (call != null) {
                        // TODO: Consider making this a queue instead of starting/stopping
                        // in quick succession.
                        mCallsManager.playDtmfTone(call, (char) dtmf);
                        mCallsManager.stopDtmfTone(call);
                        return true;
                    }
                    return false;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public String getNetworkOperator() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.gNO");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "getNetworkOperator");
                    String label = null;
                    PhoneAccount account = getBestPhoneAccount();
                    if (account != null) {
                        PhoneAccountHandle ph = account.getAccountHandle();
                        if (ph != null) {
                            String subId = ph.getId();
                            int sub;
                            try {
                                sub = Integer.parseInt(subId);
                            } catch (NumberFormatException e){
                                Log.w(this, " NumberFormatException " + e);
                                sub = SubscriptionManager.getDefaultVoiceSubscriptionId();
                            }
                            label = TelephonyManager.from(mContext)
                                    .getNetworkOperatorName(sub);
                        } else {
                            Log.w(this, "Phone Account Handle is NULL");
                        }
                    } else {
                        // Finally, just get the network name from telephony.
                        label = TelephonyManager.from(mContext)
                                .getNetworkOperatorName();
                    }
                    return label;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public String getSubscriberNumber() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.gSN");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "getSubscriberNumber");
                    String address = null;
                    PhoneAccount account = getBestPhoneAccount();
                    if (account != null) {
                        Uri addressUri = account.getAddress();
                        if (addressUri != null) {
                            address = addressUri.getSchemeSpecificPart();
                        }
                    }
                    if (TextUtils.isEmpty(address)) {
                        address = TelephonyManager.from(mContext).getLine1Number();
                        if (address == null) address = "";
                    }
                    return address;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public boolean listCurrentCalls() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.lCC");
                long token = Binder.clearCallingIdentity();
                try {
                    // only log if it is after we recently updated the headset state or else it can
                    // clog the android log since this can be queried every second.
                    boolean logQuery = mHeadsetUpdatedRecently;
                    mHeadsetUpdatedRecently = false;

                    if (logQuery) {
                        Log.i(TAG, "listcurrentCalls");
                    }

                    sendListOfCalls(logQuery);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public boolean queryPhoneState() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.qPS");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "queryPhoneState");
                    if (isDsdaEnabled()) {
                        if (mBluetoothDsda != null) {
                            try {
                                mBluetoothDsda.processQueryPhoneState();
                            } catch (RemoteException e) {
                                Log.i(TAG, "DSDA Service not found exception " + e);
                            }
                        }
                    } else {
                        updateHeadsetWithCallState(true /* force */, null);
                    }
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public boolean processChld(int chld) throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.pC");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "processChld %d", chld);
                    return BluetoothPhoneServiceImpl.this.processChld(chld);
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public void updateBtHandsfreeAfterRadioTechnologyChange() throws RemoteException {
            Log.d(TAG, "RAT change - deprecated");
            // deprecated
        }

        @Override
        public void cdmaSetSecondCallState(boolean state) throws RemoteException {
            Log.d(TAG, "cdma 1 - deprecated");
            // deprecated
        }

        @Override
        public void cdmaSwapSecondCallState() throws RemoteException {
            Log.d(TAG, "cdma 2 - deprecated");
            // deprecated
        }
    };

    /**
     * Listens to call changes from the CallsManager and calls into methods to update the bluetooth
     * headset with the new states.
     */
    @VisibleForTesting
    public CallsManagerListener mCallsManagerListener = new CallsManagerListenerBase() {
        @Override
        public void onCallAdded(Call call) {
            Log.d(TAG, "onCallAdded");
            if (call.isExternalCall()) {
                return;
            }
            if (isDsdaEnabled() && call.isConference() &&
                    (call.getChildCalls().size() == 0)) {
                Log.d(TAG, "Ignore onCallAdded for new parent call" +
                        " update headset when onIsConferencedChanged is called later");
                return;
            }
            updateHeadsetWithCallState(false /* force */, call);
        }

        @Override
        public void onCallRemoved(Call call) {
            Log.d(TAG, "onCallRemoved");
            if (call.isExternalCall()) {
                return;
            }
            mClccIndexMap.remove(call);
            updateHeadsetWithCallState(false /* force */, call);
        }

        /**
         * Where a call which was external becomes a regular call, or a regular call becomes
         * external, treat as an add or remove, respectively.
         *
         * @param call The call.
         * @param isExternalCall {@code True} if the call became external, {@code false} otherwise.
         */
        @Override
        public void onExternalCallChanged(Call call, boolean isExternalCall) {
            if (isExternalCall) {
                onCallRemoved(call);
            } else {
                onCallAdded(call);
            }
        }

        @Override
        public void onCallStateChanged(Call call, int oldState, int newState) {
            Log.d(TAG, "onCallStateChanged, call: " + call + " oldState: " + oldState +
                    " newState: " + newState);
            if (call.isExternalCall()) {
                return;
            }
            // If onCallStateChanged comes with oldState = newState when DSDA is enabled,
            // check if the call is on ActiveSub. If so, this callback is called for
            // Active Subscription change.
            if (isDsdaEnabled() && (oldState == newState)) {
                if (isCallonActiveSub(call)) {
                    Log.d(TAG, "Active subscription changed");
                    updateActiveSubChange();
                    return;
                } else {
                    Log.d(TAG, "onCallStateChanged called without any call" +
                            " state change for BG sub. Ignore updating HS");
                    return;
                }
            }
            // If a call is being put on hold because of a new connecting call, ignore the
            // CONNECTING since the BT state update needs to send out the numHeld = 1 + dialing
            // state atomically.
            // When the call later transitions to DIALING/DISCONNECTED we will then send out the
            // aggregated update.
            if (oldState == CallState.ACTIVE && newState == CallState.ON_HOLD) {
                for (Call otherCall : mCallsManager.getCalls()) {
                    if (otherCall.getState() == CallState.CONNECTING) {
                        return;
                    }
                }
            }

            // To have an active call and another dialing at the same time on Active Sub is an
            // invalid BT state. We can assume that the active call will be automatically held
            // which will send another update at which point we will be in the right state.
            Call anyActiveCall = mCallsManager.getActiveCall();
            if ((anyActiveCall != null) && oldState == CallState.CONNECTING &&
                    newState == CallState.DIALING || newState == CallState.PULLING) {
                if (!isDsdaEnabled()) {
                    return;
                } else if (isCallonActiveSub(anyActiveCall)) {
                    Log.d(TAG, "Dialing attempted on active sub when call is active");
                    return;
                }
            }
            updateHeadsetWithCallState(false /* force */, call);
        }

        @Override
        public void onIsConferencedChanged(Call call) {
            if (call.isExternalCall()) {
                return;
            }
            /*
             * Filter certain onIsConferencedChanged callbacks. Unfortunately this needs to be done
             * because conference change events are not atomic and multiple callbacks get fired
             * when two calls are conferenced together. This confuses updateHeadsetWithCallState
             * if it runs in the middle of two calls being conferenced and can cause spurious and
             * incorrect headset state updates. One of the scenarios is described below for CDMA
             * conference calls.
             *
             * 1) Call 1 and Call 2 are being merged into conference Call 3.
             * 2) Call 1 has its parent set to Call 3, but Call 2 does not have a parent yet.
             * 3) updateHeadsetWithCallState now thinks that there are two active calls (Call 2 and
             * Call 3) when there is actually only one active call (Call 3).
             */
            Log.d(TAG, "onIsConferencedChanged");
            if (call.getParentCall() != null) {
                // If this call is newly conferenced, ignore the callback. We only care about the
                // one sent for the parent conference call.
                Log.d(this, "Ignoring onIsConferenceChanged from child call with new parent");
                return;
            }
            if (call.getChildCalls().size() == 1) {
                // If this is a parent call with only one child, ignore the callback as well since
                // the minimum number of child calls to start a conference call is 2. We expect
                // this to be called again when the parent call has another child call added.
                Log.d(this, "Ignoring onIsConferenceChanged from parent with only one child call");
                return;
            }
            updateHeadsetWithCallState(false /* force */, call);
        }
    };

    /**
     * Listens to connections and disconnections of bluetooth headsets.  We need to save the current
     * bluetooth headset so that we know where to send call updates.
     */
    @VisibleForTesting
    public BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    synchronized (mLock) {
                        setBluetoothHeadset(new BluetoothHeadsetProxy((BluetoothHeadset) proxy));
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    synchronized (mLock) {
                        mBluetoothHeadset = null;
                    }
                }
            };

    /**
     * Receives events for global state changes of the bluetooth adapter.
     */
    @VisibleForTesting
    public final BroadcastReceiver mBluetoothAdapterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                int state = intent
                        .getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                Log.d(TAG, "Bluetooth Adapter state: %d", state);
                if (state == BluetoothAdapter.STATE_ON) {
                    try {
                        mBinder.queryPhoneState();
                    } catch (RemoteException e) {
                        // Remote exception not expected
                    }
                }
            }
        }
    };
        public static final String ACTIVE_SUBSCRIPTION = "active_sub";
        private boolean isCallonActiveSub(Call call){
            boolean isActiveSub = false;
            if (call.getExtras() != null &&
                (call.getExtras().containsKey(ACTIVE_SUBSCRIPTION))){
                isActiveSub = call.getExtras().getBoolean(ACTIVE_SUBSCRIPTION);
            }
            return isActiveSub;
        }

    private BluetoothAdapterProxy mBluetoothAdapter;
    private BluetoothHeadsetProxy mBluetoothHeadset;

    // A map from Calls to indexes used to identify calls for CLCC (C* List Current Calls).
    private Map<Call, Integer> mClccIndexMap = new HashMap<>();

    private boolean mHeadsetUpdatedRecently = false;

    private final Context mContext;
    private final TelecomSystem.SyncRoot mLock;
    private final CallsManager mCallsManager;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;

    public IBinder getBinder() {
        return mBinder;
    }

    public BluetoothPhoneServiceImpl(
            Context context,
            TelecomSystem.SyncRoot lock,
            CallsManager callsManager,
            BluetoothAdapterProxy bluetoothAdapter,
            PhoneAccountRegistrar phoneAccountRegistrar) {
        Log.d(this, "onCreate");

        mContext = context;
        mLock = lock;
        mCallsManager = callsManager;
        mPhoneAccountRegistrar = phoneAccountRegistrar;

        mBluetoothAdapter = bluetoothAdapter;
        if (mBluetoothAdapter == null) {
            Log.d(this, "BluetoothPhoneService shutting down, no BT Adapter found.");
            return;
        }
        mBluetoothAdapter.getProfileProxy(context, mProfileListener, BluetoothProfile.HEADSET);

        IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(mBluetoothAdapterReceiver, intentFilter);

        mTelecomManager = (TelecomManager)context.getSystemService(Context.TELECOM_SERVICE);
        if (mTelecomManager == null) {
            Log.d(TAG, "BluetoothPhoneService shutting down, TELECOM_SERVICE found.");
            return;
        }

        //Check whether we support DSDA or not
        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            Log.d(TAG, "DSDA is enabled, Bind to DSDA service");
            createBtMultiSimService();
        }

        mCallsManager.addListener(mCallsManagerListener);
        updateHeadsetWithCallState(false /* force */, null);
    }

    @VisibleForTesting
    public void setBluetoothHeadset(BluetoothHeadsetProxy bluetoothHeadset) {
        mBluetoothHeadset = bluetoothHeadset;
    }

    private void createBtMultiSimService() {
        Intent intent = new Intent(IBluetoothDsdaService.class.getName());
        intent.setComponent(intent.resolveSystemService(mContext.getPackageManager(), 0));
        if (intent.getComponent() == null || !mContext.bindService(intent,
                btMultiSimServiceConnection, Context.BIND_AUTO_CREATE)) {
            Log.i(TAG, "Ignoring IBluetoothDsdaService class not found exception ");
        } else {
            Log.d(TAG, "IBluetoothDsdaService bound request success");
        }
    }

    private ServiceConnection btMultiSimServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            //Get handle to IBluetoothDsdaService.Stub.asInterface(service);
            mBluetoothDsda = IBluetoothDsdaService.Stub.asInterface(service);
            Log.d(TAG,"Dsda Service Connected" + mBluetoothDsda);
            if (mBluetoothDsda != null) {
                Log.i(TAG, "IBluetoothDsdaService created");
            } else {
                Log.i(TAG, "IBluetoothDsdaService Error");
            }
        }

        public void onServiceDisconnected(ComponentName arg0) {
            Log.i(TAG,"DSDA Service onServiceDisconnected");
            mBluetoothDsda = null;
        }
    };

    private boolean processChld(int chld) {
        if (isDsdaEnabled() && (mBluetoothDsda != null)) {
            try {
                return processDsdaChld(chld);
            } catch (RemoteException e) {
                Log.i(TAG, " BluetoothDsdaService class not found exception " + e);
            }
        }

        Call activeCall = mCallsManager.getActiveCall();
        Call ringingCall = mCallsManager.getRingingCall();
        Call heldCall = mCallsManager.getHeldCall();

        // TODO: Keeping as Log.i for now.  Move to Log.d after L release if BT proves stable.
        Log.i(TAG, "Active: %s\nRinging: %s\nHeld: %s", activeCall, ringingCall, heldCall);

        if (chld == CHLD_TYPE_RELEASEHELD) {
            if (ringingCall != null) {
                mCallsManager.rejectCall(ringingCall, false, null);
                return true;
            } else if (heldCall != null) {
                mCallsManager.disconnectCall(heldCall);
                return true;
            }
        } else if (chld == CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD) {
            if (activeCall == null && ringingCall == null && heldCall == null)
                return false;
            if (activeCall != null) {
                mCallsManager.disconnectCall(activeCall);
                if (ringingCall != null) {
                    mCallsManager.answerCall(ringingCall, VideoProfile.STATE_AUDIO_ONLY);
                } else if (heldCall != null) {
                    mCallsManager.unholdCall(heldCall);
                }
                return true;
            }
            if (ringingCall != null) {
                mCallsManager.answerCall(ringingCall, ringingCall.getVideoState());
            } else if (heldCall != null) {
                mCallsManager.unholdCall(heldCall);
            }
            return true;
        } else if (chld == CHLD_TYPE_HOLDACTIVE_ACCEPTHELD) {
            if (activeCall != null && activeCall.can(Connection.CAPABILITY_SWAP_CONFERENCE)) {
                activeCall.swapConference();
                Log.i(TAG, "CDMA calls in conference swapped, updating headset");
                updateHeadsetWithCallState(true /* force */, activeCall);
                return true;
            } else if (ringingCall != null) {
                mCallsManager.answerCall(ringingCall, VideoProfile.STATE_AUDIO_ONLY);
                return true;
            } else if (heldCall != null) {
                // CallsManager will hold any active calls when unhold() is called on a
                // currently-held call.
                mCallsManager.unholdCall(heldCall);
                return true;
            } else if (activeCall != null && activeCall.can(Connection.CAPABILITY_HOLD)) {
                mCallsManager.holdCall(activeCall);
                return true;
            }
        } else if (chld == CHLD_TYPE_ADDHELDTOCONF) {
            if (activeCall != null) {
                if (activeCall.can(Connection.CAPABILITY_MERGE_CONFERENCE)) {
                    activeCall.mergeConference();
                    return true;
                } else {
                    List<Call> conferenceable = activeCall.getConferenceableCalls();
                    if (!conferenceable.isEmpty()) {
                        mCallsManager.conference(activeCall, conferenceable.get(0));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void enforceModifyPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    private void sendListOfCalls(boolean shouldLog) {
        Collection<Call> mCalls = mCallsManager.getCalls();
        for (Call call : mCalls) {
            // We don't send the parent conference call to the bluetooth device.
            // We do, however want to send conferences that have no children to the bluetooth
            // device (e.g. IMS Conference).
            if (!call.isConference() ||
                    (call.isConference() && call
                            .can(Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN))) {
                if (isDsdaEnabled() && (mBluetoothDsda != null)) {
                    try {
                        sendDsdaClccForCall(call, shouldLog);
                    } catch (RemoteException e) {
                        Log.i(TAG, " BluetoothDsdaService class not found exception " + e);
                    }
                } else {
                    sendClccForCall(call, shouldLog);
                }
            }
        }
        sendClccEndMarker();
    }

    /**
     * Sends a single clcc (C* List Current Calls) event for the specified call.
     */
    private void sendClccForCall(Call call, boolean shouldLog) {
        boolean isForeground = mCallsManager.getForegroundCall() == call;
        int state = convertCallState(call.getState(), isForeground);
        boolean isPartOfConference = false;
        boolean isConferenceWithNoChildren = call.isConference() && call
                .can(Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN);

        if (state == CALL_STATE_IDLE) {
            return;
        }

        Call conferenceCall = call.getParentCall();
        if (conferenceCall != null) {
            isPartOfConference = true;

            // Run some alternative states for Conference-level merge/swap support.
            // Basically, if call supports swapping or merging at the conference-level, then we need
            // to expose the calls as having distinct states (ACTIVE vs CAPABILITY_HOLD) or the
            // functionality won't show up on the bluetooth device.

            // Before doing any special logic, ensure that we are dealing with an ACTIVE call and
            // that the conference itself has a notion of the current "active" child call.
            Call activeChild = conferenceCall.getConferenceLevelActiveCall();
            if (state == CALL_STATE_ACTIVE && activeChild != null) {
                // Reevaluate state if we can MERGE or if we can SWAP without previously having
                // MERGED.
                boolean shouldReevaluateState =
                        conferenceCall.can(Connection.CAPABILITY_MERGE_CONFERENCE) ||
                        (conferenceCall.can(Connection.CAPABILITY_SWAP_CONFERENCE) &&
                        !conferenceCall.wasConferencePreviouslyMerged());

                if (shouldReevaluateState) {
                    isPartOfConference = false;
                    if (call == activeChild) {
                        state = CALL_STATE_ACTIVE;
                    } else {
                        // At this point we know there is an "active" child and we know that it is
                        // not this call, so set it to HELD instead.
                        state = CALL_STATE_HELD;
                    }
                }
            }
        } else if (isConferenceWithNoChildren) {
            // Handle the special case of an IMS conference call without conference event package
            // support.  The call will be marked as a conference, but the conference will not have
            // child calls where conference event packages are not used by the carrier.
            isPartOfConference = true;
        }

        int index = getIndexForCall(call);
        int direction = call.isIncoming() ? 1 : 0;
        final Uri addressUri;
        if (call.getGatewayInfo() != null) {
            addressUri = call.getGatewayInfo().getOriginalAddress();
        } else {
            addressUri = call.getHandle();
        }
        String address = addressUri == null ? null : addressUri.getSchemeSpecificPart();
        int addressType = address == null ? -1 : PhoneNumberUtils.toaFromString(address);

        if (shouldLog) {
            Log.i(this, "sending clcc for call %d, %d, %d, %b, %s, %d",
                    index, direction, state, isPartOfConference, Log.piiHandle(address),
                    addressType);
        }

        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(
                    index, direction, state, 0, isPartOfConference, address, addressType);
        }
    }

    /**
     * Sends a single clcc (C* List Current Calls) event for the specified call in DSDA scenario.
     */
    private void sendDsdaClccForCall(Call call, boolean shouldLog) throws  RemoteException {
        boolean isForeground = mCallsManager.getForegroundCall() == call;
        boolean isPartOfConference = false;
        boolean isActive = false;
        boolean allowDsda = false;
        int state = convertCallState(call.getState(), isForeground);
        int subForCall = Integer.parseInt(call.getTargetPhoneAccount().getId());
        int activeSub = getActiveSubscription();
        if (INVALID_SUBID == activeSub) {
            Log.i(TAG, "Invalid activeSub id, returning");
            return;
        }

        Call activeSubForegroundCall = mCallsManager.getFirstCallWithState(
                Integer.toString(activeSub), LIVE_CALL_STATES);
        Call activeSubRingingCall = mCallsManager.getFirstCallWithState(
                Integer.toString(activeSub), CallState.RINGING);
        Call activeSubBackgroundCall = mCallsManager.getFirstCallWithState(
                Integer.toString(activeSub), CallState.ON_HOLD);

        if (getBluetoothCallStateForUpdate() != CALL_STATE_IDLE) {
            allowDsda = true;
            Log.i(this, "Call setup in progress, allowDsda: " + allowDsda);
        }

        Log.i(this, "CLCC on SUB: " + subForCall + " CallState: " + state);

        if (state == CALL_STATE_IDLE) {
            return;
        }

        if (call == activeSubRingingCall) {
            Log.i(this, "This is FG Ringing call on active sub");
            isActive = true;
        } else if (call == activeSubForegroundCall) {
            Log.i(this, "This is FG live call on active sub");
            isActive = true;
        } else if (call == activeSubBackgroundCall) {
            Log.i(this, "This is BG call on active sub");
        }

        Call conferenceCall = call.getParentCall();
        if (conferenceCall != null) {
            Log.i(this, "This call has parent call");
            isPartOfConference = true;
            Call activeChild = conferenceCall.getConferenceLevelActiveCall();
            if (state == CALL_STATE_ACTIVE && activeChild != null) {
                boolean shouldReevaluateState =
                        conferenceCall.can(Connection.CAPABILITY_MERGE_CONFERENCE) ||
                        (conferenceCall.can(Connection.CAPABILITY_SWAP_CONFERENCE) &&
                        !conferenceCall.wasConferencePreviouslyMerged());

                Log.i(this, "shouldReevaluateState: " + shouldReevaluateState);
                if (shouldReevaluateState) {
                    isPartOfConference = false;
                    if (call == activeChild) {
                        Log.i(this, "this is active child");
                        if ((mBluetoothDsda.hasCallsOnBothSubs() == true) &&
                                isCallonActiveSub(activeChild)) {
                            isActive = true;
                        }
                        state = CALL_STATE_ACTIVE;
                    } else {
                        Log.i(this, "this is not active child");
                        if ((mBluetoothDsda.hasCallsOnBothSubs() == true) &&
                             isCallonActiveSub(call)) {
                            isActive = false;
                        }
                        state = CALL_STATE_HELD;
                    }
                }
            }
        }

        if (call != null) {
            if (mBluetoothDsda.isFakeMultiPartyCall() && !isActive) {
                Log.i(this, "A fake mparty scenario");
                isPartOfConference = true;
            }
        }

        //Special case:
        //Sub1: 1A(isPartOfConference=true), 2A(isPartOfConference=true)
        //Sub2: 3A(isPartOfConference should set to true), 4W(isPartOfConference=false)
        if ((mBluetoothDsda.hasCallsOnBothSubs() == true) && isCallonActiveSub(call)
                && (activeSubRingingCall != null) && (call != activeSubRingingCall)) {
            Log.i(this, "A fake mparty special scenario");
            isPartOfConference = true;
        }
        Log.i(this, "call.isPartOfConference: " + isPartOfConference);

        int index = getIndexForCall(call);
        int direction = call.isIncoming() ? 1 : 0;
        final Uri addressUri;
        if (call.getGatewayInfo() != null) {
            addressUri = call.getGatewayInfo().getOriginalAddress();
        } else {
            addressUri = call.getHandle();
        }
        String address = addressUri == null ? null : addressUri.getSchemeSpecificPart();
        int addressType = address == null ? -1 : PhoneNumberUtils.toaFromString(address);

        if (mCallsManager.hasActiveOrHoldingCall()) {
            if (state == CALL_STATE_INCOMING) {
                Log.i(this, "hasActiveOrHoldingCall(), If Incoming, make it Waiting");
                state = CALL_STATE_WAITING; //DSDA
            }
        }

       // If calls on both Subs, need to change call states on BG calls
       if (((mBluetoothDsda.hasCallsOnBothSubs() == true) || allowDsda) && !isActive) {
           Log.i(this, "Calls on both Subs, manage call state for BG sub calls");
           int activeCallState = convertCallState(
                   (activeSubRingingCall != null) ? activeSubRingingCall.getState():
                   CallState.NEW, (activeSubForegroundCall !=null) ?
                   activeSubForegroundCall.getState(): CallState.NEW);
           Log.i(this, "state : " + state + "activeCallState: " + activeCallState);
           //Fake call held for all background calls
           if ((state == CALL_STATE_ACTIVE) && (activeCallState != CALL_STATE_INCOMING)) {
               state = CALL_STATE_HELD;
           } else if (isPartOfConference == true) {
               Log.i(this, "isPartOfConference, manage call states on BG sub");
               if (activeCallState != CALL_STATE_INCOMING) {
                   state = CALL_STATE_HELD;
               } else if (activeCallState == CALL_STATE_INCOMING) {
                   state = CALL_STATE_ACTIVE;
               }
           }
           Log.i(this, "state of this BG Sub call: " + state);
        }

        if (shouldLog) {
            Log.i(this, "sending clcc for call %d, %d, %d, %b, %s, %d",
                    index, direction, state, isPartOfConference, Log.piiHandle(address),
                    addressType);
        }

        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(
                    index, direction, state, 0, isPartOfConference, address, addressType);
        } else {
            Log.i(this, "headset null, no need to send clcc");
        }
    }

    private void sendClccEndMarker() {
        // End marker is recognized with an index value of 0. All other parameters are ignored.
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(0 /* index */, 0, 0, 0, false, null, 0);
        }
    }

    /**
     * Returns the caches index for the specified call.  If no such index exists, then an index is
     * given (smallest number starting from 1 that isn't already taken).
     */
    private int getIndexForCall(Call call) {
        if (mClccIndexMap.containsKey(call)) {
            return mClccIndexMap.get(call);
        }

        int i = 1;  // Indexes for bluetooth clcc are 1-based.
        while (mClccIndexMap.containsValue(i)) {
            i++;
        }

        // NOTE: Indexes are removed in {@link #onCallRemoved}.
        mClccIndexMap.put(call, i);
        return i;
    }

    private void updateActiveSubChange() {
        Log.d(TAG, "update ActiveSubChange to DSDA service");
        if (isDsdaEnabled() && (mBluetoothDsda != null)) {
            try {
                mBluetoothDsda.phoneSubChanged();
            } catch (RemoteException e) {
                Log.w(TAG, "DSDA class not found exception " + e);
            }
        }
    }

    /**
     * Sends an update of the current call state to the current Headset.
     *
     * @param force {@code true} if the headset state should be sent regardless if no changes to the
     *      state have occurred, {@code false} if the state should only be sent if the state has
     *      changed.
     * @ param call is specified call for which Headset is to be updated.
     */
    private void updateHeadsetWithCallState(boolean force, Call call) {
        if (isDsdaEnabled() && (call != null)) {
            Log.d(TAG, "DSDA call operation, handle it separately");
            updateDsdaServiceWithCallState(call);
        } else {
            CallsManager callsManager = mCallsManager;
            Call activeCall = mCallsManager.getActiveCall();
            Call ringingCall = mCallsManager.getRingingCall();
            Call heldCall = mCallsManager.getHeldCall();

            int bluetoothCallState = getBluetoothCallStateForUpdate();

            String ringingAddress = null;
            int ringingAddressType = 128;
            if (ringingCall != null && ringingCall.getHandle() != null) {
                ringingAddress = ringingCall.getHandle().getSchemeSpecificPart();
                if (ringingAddress != null) {
                    ringingAddressType = PhoneNumberUtils.toaFromString(ringingAddress);
                }
            }
            if (ringingAddress == null) {
                ringingAddress = "";
            }

            int numActiveCalls = activeCall == null ? 0 : 1;
            int numHeldCalls = callsManager.getNumHeldCalls();
            boolean callsSwitched = (numHeldCalls == 2);
            // For conference calls which support swapping the active call within the conference
            // (namely CDMA calls) we need to expose that as a held call in order for the BT device
            // to show "swap" and "merge" functionality.
            boolean ignoreHeldCallChange = false;
            if (activeCall != null && activeCall.isConference() &&
                !activeCall.can(Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN)) {
                if (activeCall.can(Connection.CAPABILITY_SWAP_CONFERENCE)) {
                    // Indicate that BT device should show SWAP command by indicating that there
                    // is a call on hold, but only if the conference wasn't previously merged.
                    numHeldCalls = activeCall.wasConferencePreviouslyMerged() ? 0 : 1;
                } else if (activeCall.can(Connection.CAPABILITY_MERGE_CONFERENCE)) {
                    numHeldCalls = 1;  // Merge is available, so expose via numHeldCalls.
                }

                for (Call childCall : activeCall.getChildCalls()) {
                    // Held call has changed due to it being combined into a CDMA conference. Keep
                    // track of this and ignore any future update since it doesn't really count as
                    // a call change.
                    if (mOldHeldCall == childCall) {
                        ignoreHeldCallChange = true;
                        break;
                    }
                }
            }

            if (mBluetoothHeadset != null &&
                    (numActiveCalls != mNumActiveCalls ||
                    numHeldCalls != mNumHeldCalls ||
                    bluetoothCallState != mBluetoothCallState ||
                    !TextUtils.equals(ringingAddress, mRingingAddress) ||
                    ringingAddressType != mRingingAddressType ||
                    (heldCall != mOldHeldCall && !ignoreHeldCallChange) ||
                    force) && !callsSwitched) {

                // If the call is transitioning into the alerting state, send DIALING first.
                // Some devices expect to see a DIALING state prior to seeing an ALERTING state
                // so we need to send it first.
                boolean sendDialingFirst = mBluetoothCallState != bluetoothCallState &&
                        bluetoothCallState == CALL_STATE_ALERTING;

                if (numActiveCalls > 0) {
                Log.i(TAG, "updateHeadsetWithCallState: Call active");
                boolean isCsCall = ((activeCall != null) &&
                        !(activeCall.hasProperty(Connection.PROPERTY_HIGH_DEF_AUDIO) ||
                        activeCall.hasProperty(Connection.PROPERTY_WIFI)));
                final Intent intent = new Intent(TelecomManager.ACTION_CALL_TYPE);
                intent.putExtra(TelecomManager.EXTRA_CALL_TYPE_CS, isCsCall);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
                mOldHeldCall = heldCall;
                mNumActiveCalls = numActiveCalls;
                mNumHeldCalls = numHeldCalls;
                mBluetoothCallState = bluetoothCallState;
                mRingingAddress = ringingAddress;
                mRingingAddressType = ringingAddressType;

                if (sendDialingFirst) {
                    // Log in full to make logs easier to debug.
                    Log.i(TAG, "updateHeadsetWithCallState " +
                            "numActive %s, " +
                            "numHeld %s, " +
                            "callState %s, " +
                            "ringing number %s, " +
                            "ringing type %s",
                            mNumActiveCalls,
                            mNumHeldCalls,
                            CALL_STATE_DIALING,
                            Log.pii(mRingingAddress),
                            mRingingAddressType);
                    mBluetoothHeadset.phoneStateChanged(
                            mNumActiveCalls,
                            mNumHeldCalls,
                            CALL_STATE_DIALING,
                            mRingingAddress,
                            mRingingAddressType);
                }


                Log.i(TAG, "updateHeadsetWithCallState " +
                        "numActive %s, " +
                        "numHeld %s, " +
                        "callState %s, " +
                        "ringing number %s, " +
                        "ringing type %s",
                        mNumActiveCalls,
                        mNumHeldCalls,
                        mBluetoothCallState,
                        Log.pii(mRingingAddress),
                        mRingingAddressType);

                mBluetoothHeadset.phoneStateChanged(
                        mNumActiveCalls,
                        mNumHeldCalls,
                        mBluetoothCallState,
                        mRingingAddress,
                        mRingingAddressType);

                mHeadsetUpdatedRecently = true;
            }
        }
    }

    /**
     * Sends an update of the current dsda call state to the Dsda service.
     *
     * @param call is specified call for which Headset is to be updated.
     */
    private void updateDsdaServiceWithCallState(Call call) {
        CallsManager callsManager = mCallsManager;
        int subscription = INVALID_SUBID;
        if (mBluetoothDsda != null) {
            Log.d(TAG, "Get the Sub on which call state change happened");
            if (call.getTargetPhoneAccount() != null) {
                String sub = call.getTargetPhoneAccount().getId();
                subscription = SubscriptionManager.getDefaultVoiceSubscriptionId();
                try {
                    subscription = Integer.parseInt(sub);
                } catch (NumberFormatException e) {
                    Log.w(this, " NumberFormatException " + e);
                }
            } else if (call.isConference()) {
                for (Call childCall : call.getChildCalls()) {
                    if (childCall.getTargetPhoneAccount() != null) {
                        String sub = childCall.getTargetPhoneAccount().getId();
                        subscription = SubscriptionManager.getDefaultVoiceSubscriptionId();
                        try {
                            subscription = Integer.parseInt(sub);
                        } catch (NumberFormatException e) {
                            Log.w(this, " NumberFormatException " + e);
                        }
                    } else {
                        Log.w(this, "PhoneAccountHandle is NULL for childCall: " + childCall);
                    }
                    if (subscription != INVALID_SUBID)
                        break;
                }
            } else {
                Log.w(this, "PhoneAccountHandle is NULL");
            }

            Log.d(TAG, "SUB on which call state to be updated " + subscription);
            if (subscription == INVALID_SUBID) {
                return;
            }

            try {
                mBluetoothDsda.setCurrentSub(subscription);
                Call ringCall = callsManager.getFirstCallWithState(
                        Integer.toString(subscription), CallState.RINGING);
                int ringingCallState = callsManager.hasRingingCall() ?
                        CallState.RINGING : CallState.NEW;

                Call foregroundCall = callsManager.getFirstCallWithState(
                        Integer.toString(subscription), LIVE_CALL_STATES);
                int foregroundCallState = (foregroundCall != null) ?
                        foregroundCall.getState() : CallState.NEW;

                Call backgroundCall = callsManager.getFirstCallWithState(
                        Integer.toString(subscription), CallState.ON_HOLD);
                int backgroundCallState = (backgroundCall != null) ?
                        CallState.ON_HOLD: CallState.NEW;

                Log.d(TAG, "callsManager.getActiveCall()  =  " + callsManager.getActiveCall());
                int numHeldCallsonSub = getDsdaNumHeldCalls(callsManager.getActiveCall(),
                        subscription, mOldHeldCall);

                String ringingAddress = null;
                int ringingAddressType = 128;
                if (ringCall != null) {
                    ringingAddress = ringCall.getHandle().getSchemeSpecificPart();
                    if (ringingAddress != null) {
                        ringingAddressType = PhoneNumberUtils.toaFromString(ringingAddress);
                    }
                }
                if (ringingAddress == null) {
                    ringingAddress = "";
                }
                mBluetoothDsda.handleMultiSimPreciseCallStateChange(foregroundCallState,
                        ringingCallState, ringingAddress, ringingAddressType,
                        backgroundCallState, numHeldCallsonSub);
            } catch (RemoteException e) {
                Log.i(TAG, "Ignoring DSDA class not found exception " + e);
            }
        }
        return;
    }

    private int getDsdaNumHeldCalls(Call activeCall, int subscription, Call mOldHeldCall) {
        // For conference calls which support swapping the active call within the conference
        // (namely CDMA calls) we need to expose that as a held call in order for the BT device
        // to show "swap" and "merge" functionality.
        int numHeldCalls = 0;
        int activeCallSub = 0;

        if (activeCall != null && activeCall.isConference()) {
            if (activeCall.getTargetPhoneAccount() != null) {
                String sub = activeCall.getTargetPhoneAccount().getId();
                activeCallSub = SubscriptionManager.getDefaultVoiceSubscriptionId();
                try {
                    activeCallSub = Integer.parseInt(sub);
                } catch (NumberFormatException e) {
                    Log.w(this, " NumberFormatException " + e);
                }
            } else {
                for (Call childCall : activeCall.getChildCalls()) {
                    if (childCall.getTargetPhoneAccount() != null) {
                        String sub = childCall.getTargetPhoneAccount().getId();
                        activeCallSub = SubscriptionManager.getDefaultVoiceSubscriptionId();
                        try {
                            activeCallSub = Integer.parseInt(sub);
                        } catch (NumberFormatException e) {
                            Log.w(this, " NumberFormatException " + e);
                        }
                    } else {
                        Log.w(this, "PhoneAccountHandle is NULL for childCall: " + childCall);
                    }
                    if (activeCallSub != INVALID_SUBID)
                        break;
                }
            }
            if (activeCallSub == subscription) {
                if (activeCall.can(Connection.CAPABILITY_SWAP_CONFERENCE)) {
                    // Indicate that BT device should show SWAP command by indicating that there
                    // is a call on hold, but only if the conference wasn't previously merged.
                    numHeldCalls = activeCall.wasConferencePreviouslyMerged() ? 0 : 1;
                } else if (activeCall.can(Connection.CAPABILITY_MERGE_CONFERENCE)) {
                    numHeldCalls = 1;  // Merge is available, so expose via numHeldCalls.
                }
                Log.i(TAG, "getDsdaNumHeldCalls: numHeldCalls:  " + numHeldCalls);
                return numHeldCalls;
            }
        }
        numHeldCalls = getNumCallsWithState(Integer.toString(subscription), CallState.ON_HOLD);
        Log.i(TAG, "getDsdaNumHeldCalls: numHeldCalls = " + numHeldCalls);
        return numHeldCalls;
    }
    private int getBluetoothCallStateForUpdate() {
        CallsManager callsManager = mCallsManager;
        Call ringingCall = mCallsManager.getRingingCall();
        Call dialingCall = mCallsManager.getOutgoingCall();

        //
        // !! WARNING !!
        // You will note that CALL_STATE_WAITING, CALL_STATE_HELD, and CALL_STATE_ACTIVE are not
        // used in this version of the call state mappings.  This is on purpose.
        // phone_state_change() in btif_hf.c is not written to handle these states. Only with the
        // listCalls*() method are WAITING and ACTIVE used.
        // Using the unsupported states here caused problems with inconsistent state in some
        // bluetooth devices (like not getting out of ringing state after answering a call).
        //
        int bluetoothCallState = CALL_STATE_IDLE;
        if (ringingCall != null) {
            bluetoothCallState = CALL_STATE_INCOMING;
        } else if (dialingCall != null) {
            bluetoothCallState = CALL_STATE_ALERTING;
        }
        return bluetoothCallState;
    }

    private int convertCallState(int ringingState, int foregroundState) {
        if (ringingState == CallState.RINGING)
            return CALL_STATE_INCOMING;
        else if (foregroundState == CallState.DIALING)
            return CALL_STATE_ALERTING;
        else
            return CALL_STATE_IDLE;
    }

    private int convertCallState(int callState, boolean isForegroundCall) {
        switch (callState) {
            case CallState.NEW:
            case CallState.ABORTED:
            case CallState.DISCONNECTED:
                return CALL_STATE_IDLE;

            case CallState.ACTIVE:
                return CALL_STATE_ACTIVE;

            case CallState.CONNECTING:
            case CallState.SELECT_PHONE_ACCOUNT:
            case CallState.DIALING:
            case CallState.PULLING:
                // Yes, this is correctly returning ALERTING.
                // "Dialing" for BT means that we have sent information to the service provider
                // to place the call but there is no confirmation that the call is going through.
                // When there finally is confirmation, the ringback is played which is referred to
                // as an "alert" tone, thus, ALERTING.
                // TODO: We should consider using the ALERTING terms in Telecom because that
                // seems to be more industry-standard.
                return CALL_STATE_ALERTING;

            case CallState.ON_HOLD:
                return CALL_STATE_HELD;

            case CallState.RINGING:
                if (isForegroundCall) {
                    return CALL_STATE_INCOMING;
                } else {
                    return CALL_STATE_WAITING;
                }
        }
        return CALL_STATE_IDLE;
    }

    /**
     * Returns the best phone account to use for the given state of all calls.
     * First, tries to return the phone account for the foreground call, second the default
     * phone account for PhoneAccount.SCHEME_TEL.
     */
    private PhoneAccount getBestPhoneAccount() {
        if (mPhoneAccountRegistrar == null) {
            return null;
        }

        Call call = mCallsManager.getForegroundCall();

        PhoneAccount account = null;
        if (call != null) {
            // First try to get the network name of the foreground call.
            account = mPhoneAccountRegistrar.getPhoneAccountOfCurrentUser(
                    call.getTargetPhoneAccount());
        }

        if (account == null) {
            // Second, Try to get the label for the default Phone Account.
            account = mPhoneAccountRegistrar.getPhoneAccountUnchecked(
                    mPhoneAccountRegistrar.getOutgoingPhoneAccountForSchemeOfCurrentUser(
                            PhoneAccount.SCHEME_TEL));
        }
        return account;
    }

    private boolean isDsdaEnabled() {
        //Check whether we support DSDA or not
        if ((TelephonyManager.getDefault().getMultiSimConfiguration()
                == TelephonyManager.MultiSimVariants.DSDA)) {
            Log.d(TAG, "DSDA is enabled");
            return true;
        }
        return false;
    }

    private int getNumCallsWithState(String subId, int state) {
        int count = 0;
        CallsManager callsManager = mCallsManager;
        for (Call call : callsManager.getCalls()) {
            if (call.getState() == state && call.getTargetPhoneAccount() != null
                    && call.getTargetPhoneAccount().getId().equals(subId)) {
                count++;
            }
        }
        return count;
    }

    private int getActiveSubscription() {
        String activeSub = mCallsManager.getActiveSubscription();
        return (activeSub == null) ? SubscriptionManager.INVALID_SUBSCRIPTION_ID:
            Integer.parseInt(activeSub);
    }

    private boolean processDsdaChld(int chld) throws  RemoteException {
        boolean status = true;
        CallsManager callsManager = mCallsManager;
        Log.i(TAG, "processDsdaChld: " + chld );
        int activeSub = getActiveSubscription();
        Log.i(TAG, "activeSub: " + activeSub);
        if (INVALID_SUBID == activeSub) {
            Log.i(TAG, "Invalid activeSub id, returning");
            return false;
        }

        Call activeCall = callsManager.getActiveCall();
        Call ringingCall = callsManager.getFirstCallWithState(
                Integer.toString(activeSub), CallState.RINGING);
        Call backgroundCall = callsManager.getFirstCallWithState(
                Integer.toString(activeSub), CallState.ON_HOLD);

        switch (chld) {
            case CHLD_TYPE_RELEASEHELD:
                if (ringingCall != null) {
                    callsManager.rejectCall(ringingCall, false, null);
                } else {
                    Call call = getCallOnOtherSub(false);
                    if (call != null) {
                        callsManager.disconnectCall(call);
                    } else {
                        if (backgroundCall != null) {
                            callsManager.disconnectCall(backgroundCall);
                        }
                    }
                }
                status = true;
                break;

            case CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD:
                Call call = getCallOnOtherSub(false);
                if ((ringingCall != null) && (call != null)) {
                    //first answer the incoming call
                    callsManager.answerCall(ringingCall, 0);
                    //Try to Drop the call on the other SUB.
                    callsManager.disconnectCall(call);
                } else if (mBluetoothDsda.isSwitchSubAllowed()) {
                    /* In case of Sub1=Active and Sub2=lch/held, drop call
                       on active  Sub */
                    Log.i(TAG, "processChld drop the call on Active sub, move LCH to active");
                    call = getCallOnOtherSub(true);
                    if (call != null) {
                        callsManager.disconnectCall(call);
                    }
                } else {
                    if (activeCall != null) {
                        Log.i(TAG, "Dropping active call");
                        callsManager.disconnectCall(activeCall);
                        if (ringingCall != null) {
                            callsManager.answerCall(ringingCall, 0);
                        } else if (backgroundCall != null) {
                            callsManager.unholdCall(backgroundCall);
                        }
                    }
                }
                status = true;
                break;

            case CHLD_TYPE_HOLDACTIVE_ACCEPTHELD:
                if (mBluetoothDsda.canDoCallSwap()) {
                    Log.i(TAG, "Try to do call swap on same sub");
                    if (activeCall != null && activeCall.can(Connection.CAPABILITY_SWAP_CONFERENCE)) {
                        activeCall.swapConference();
                    } else if (backgroundCall != null) {
                        callsManager.unholdCall(backgroundCall);
                    }
                } else if (mBluetoothDsda.isSwitchSubAllowed()) {
                    /*Switch SUB*/
                    Log.i(TAG, "Switch sub");
                    // If there is a change in active subscription while both the
                    // subscriptions are in active state, need to siwtch the
                    // playing of LCH/SCH tone to new LCH subscription.
                    mBluetoothDsda.switchSub();
                } else if (mBluetoothDsda.answerOnThisSubAllowed() == true) {
                    Log.i(TAG, "Can we answer the call on other SUB?");
                    /* Answer the call on current SUB*/
                    if (ringingCall != null)
                        callsManager.answerCall(ringingCall, 0);
                } else {
                    Log.i(TAG, "CHLD=2, Answer the call on same sub");
                    if (ringingCall != null) {
                        Log.i(TAG, "Background is on hold when incoming call came");
                        callsManager.answerCall(ringingCall, 0);
                    } else if (backgroundCall != null) {
                        callsManager.unholdCall(backgroundCall);
                    } else if (activeCall != null && activeCall.can(Connection.CAPABILITY_HOLD)) {
                        Log.i(TAG, "Only active call, put that to hold");
                        callsManager.holdCall(activeCall);
                    }
                }
                status = true;
                break;

            case CHLD_TYPE_ADDHELDTOCONF:
                if (activeCall != null) {
                    if (activeCall.can(Connection.CAPABILITY_MERGE_CONFERENCE)) {
                        activeCall.mergeConference();
                    } else {
                        List<Call> conferenceable = activeCall.getConferenceableCalls();
                        if (!conferenceable.isEmpty()) {
                            callsManager.conference(activeCall, conferenceable.get(0));
                        }
                    }
                }
                status = true;
                break;

            default:
                Log.i(TAG, "bad CHLD value: " + chld);
                status = false;
                break;
        }
        return status;
    }

    /* Get the active or held call on other Sub. */
    private Call getCallOnOtherSub(boolean isActive) throws  RemoteException {
        CallsManager callsManager = mCallsManager;
        Log.d(TAG, "getCallOnOtherSub, isActiveSub call required: " + isActive);
        int activeSub = getActiveSubscription();
        if (INVALID_SUBID == activeSub) {
            Log.i(TAG, "Invalid activeSub id, returning");
            return null;
        }
        int bgSub = getOtherActiveSub(activeSub);
        /*bgSub would be INVALID_SUBID when bg subscription has no calls*/
        if (bgSub == INVALID_SUBID) {
            return null;
        }

        Call call = null;
        if (isActive) {
            if (mBluetoothDsda.getTotalCallsOnSub(bgSub) < 2 ) {
                if (callsManager.hasActiveOrHoldingCall(Integer.toString(activeSub)))
                    call = callsManager.getFirstCallWithState(
                            Integer.toString(activeSub), CallState.ACTIVE, CallState.ON_HOLD);
            }
        } else {
            if (mBluetoothDsda.getTotalCallsOnSub(bgSub) == 1) {
                if (callsManager.hasActiveOrHoldingCall(Integer.toString(bgSub)))
                    call = callsManager.getFirstCallWithState(
                            Integer.toString(bgSub), CallState.ACTIVE, CallState.ON_HOLD);
            }
        }
        return call;
    }

    private int getOtherActiveSub(int activeSub) {
        boolean subSwitched = false;
        for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
            int[] subId = SubscriptionManager.getSubId(i);
            if (subId[0] != activeSub) {
                Log.i(TAG, "other Sub: " + subId[0]);
                return subId[0];
            }
        }
        return INVALID_SUBID;
    }
}
