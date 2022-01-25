/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.telecom.Call.Callback.ANSWER_FAILED_ENDPOINT_REJECTED;
import static android.telecom.Call.Callback.ANSWER_FAILED_ENDPOINT_TIMEOUT;
import static android.telecom.Call.Callback.ANSWER_FAILED_ENDPOINT_UNAVAILABLE;
import static android.telecom.Call.Callback.ANSWER_FAILED_UNKNOWN_REASON;
import static android.telecom.Call.Callback.PUSH_FAILED_ENDPOINT_REJECTED;
import static android.telecom.Call.Callback.PUSH_FAILED_ENDPOINT_TIMEOUT;
import static android.telecom.Call.Callback.PUSH_FAILED_ENDPOINT_UNAVAILABLE;
import static android.telecom.Call.Callback.PUSH_FAILED_UNKNOWN_REASON;
import static android.telecom.Call.STATE_ACTIVE;

import android.content.Context;
import android.os.RemoteException;
import android.telecom.CallEndpoint;
import android.telecom.CallEndpointSession;
import android.telecom.Connection;
import android.telecom.Log;
import android.telecom.Logging.Runnable;
import android.telecom.VideoProfile;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.bluetooth.BluetoothStateReceiver;
import com.android.server.telecom.ui.AudioProcessingNotification;
import com.android.server.telecom.ui.DisconnectedCallNotifier;
import com.android.server.telecom.ui.ToastFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class CallEndpointController extends CallsManagerListenerBase {
    private static final long ENDPOINT_TIMEOUT = 5000L;

    private final TelecomSystem.SyncRoot mTelecomLock;
    private final CallsManager mCallsManager;
    private final Set<CallEndpoint> mCallEndpoints;
    private final InCallController mInCallController;
    private final HashMap<Call, CallEndpointSessionTracker> mSessionsByCall = new HashMap<>();
    private final CallsManagerAdapter mCallsManagerAdapter;
    private final Object mLock;

    private final class CallsManagerAdapter {
        private final CallsManager mCallsManager;

        /**
         * Initializes the required Telecom components.
         */
        public CallsManagerAdapter(CallsManager callsManager) {
            mCallsManager = callsManager;
        }

        public void updateAvailableCallEndpoints(Set<CallEndpoint> availableCallEndpoints) {
            mCallsManager.updateAvailableCallEndpoints(availableCallEndpoints);
        }
    }

    private final SystemStateHelper.SystemStateListener mSystemStateListener =
            new SystemStateHelper.SystemStateListener() {
                @Override
                public void onCarModeChanged(int priority, String packageName, boolean isCarMode) {
                    // ignore
                }

                @Override
                public void onAutomotiveProjectionStateSet(String automotiveProjectionPackage) {
                    // ignore
                }

                @Override
                public void onAutomotiveProjectionStateReleased() {
                    // ignore
                }

                @Override
                public void onPackageUninstalled(String packageName) {
                    // Handle uninstalled packages
                    Set<CallEndpoint> removed = new HashSet<>();

                    synchronized (mLock) {
                        for (CallEndpoint callEndpoint : mCallEndpoints) {
                            if (callEndpoint.getComponentName() != null
                                    && callEndpoint.getComponentName().getPackageName().equals(
                                    packageName)) {
                                mSessionsByCall.forEach((call, session) -> {
                                    if (session.getCallEndpoint().equals(callEndpoint)) {
                                        notifySessionDeactivated(call);
                                        mCallsManager.disconnectCall(call);
                                    }
                                });
                                removed.add(callEndpoint);
                            }
                        }
                    }

                    if (!removed.isEmpty()) {
                        unregisterCallEndpoints(new ArrayList<>(removed));
                    }
                }
            };

    public CallEndpointController(TelecomSystem.SyncRoot lock, CallsManager callsManager) {
        mTelecomLock = lock;
        mCallsManager = callsManager;
        mCallsManager.getSystemStateHelper().addListener(mSystemStateListener);
        mInCallController = callsManager.getInCallController();
        mCallEndpoints = new HashSet<>();
        mLock = new Object();
        mCallsManager.addListener(this);
        mCallsManagerAdapter = new CallsManagerAdapter(callsManager);
    }

    public void registerCallEndpoints(List<CallEndpoint> endpoints) {
        boolean updated = false;

        synchronized (mLock) {
            for (CallEndpoint e : endpoints) {
                updated ^= mCallEndpoints.add(e);
            }
        }

        if (updated) {
            mCallsManagerAdapter.updateAvailableCallEndpoints(Set.copyOf(mCallEndpoints));
        }
    }

    public void unregisterCallEndpoints(List<CallEndpoint> endpoints) {
        boolean updated = false;

        synchronized (mLock) {
            for (CallEndpoint e : endpoints) {
                updated ^= mCallEndpoints.remove(e);
            }
        }

        if (updated) {
            mCallsManagerAdapter.updateAvailableCallEndpoints(Set.copyOf(mCallEndpoints));
        }
    }

    public void requestPlaceCall(Call call, CallEndpoint callEndpoint) {
        synchronized (mLock) {
            if (!mCallEndpoints.contains(callEndpoint)) {
                // unavailable call endpoint: disconnect the call or fallback to normal call?
                // Current implementation: disconnect the call.
                Log.i(this, "requestPlaceCall failed due to unavailable callEndpoint: "
                        + callEndpoint);
                mCallsManager.disconnectCall(call);
                return;
            }

            InternalCallEndpointSession session = new InternalCallEndpointSession(call,
                    callEndpoint, this);
            CallEndpointSessionTracker tracker = new CallEndpointSessionTracker(session,
                    CallEndpointSession.PLACE_REQUEST);
            call.setActiveCallEndpoint(callEndpoint);
            mInCallController.requestStreamingCall(tracker);
            mSessionsByCall.put(call, tracker);

            tracker.getHandler().postDelayed(new Runnable("CEC.rPlaceC", mTelecomLock) {
                @Override
                public void loggedRun() {
                    // Check if the request have been handled. If not, notify timeout to the
                    // endpoint and dialer call.
                    if (!tracker.isRequestHandled()) {
                        onCallEndpointSessionActivationTimedout(call);
                        mCallsManager.disconnectCall(call);
                    }
                }
            }.prepare(), ENDPOINT_TIMEOUT);
        }
    }

    public void requestAnswerCall(Call call, CallEndpoint callEndpoint,
            @VideoProfile.VideoState int videoState) {
        synchronized (mLock) {
            if (!mCallEndpoints.contains(callEndpoint)) {
                // unavailable call endpoint
                Log.i(this, "requestAnswerCall failed due to unavailable callEndpoint: "
                        + callEndpoint);
                call.onAnswerFailed(callEndpoint, ANSWER_FAILED_ENDPOINT_UNAVAILABLE);
                mCallsManager.disconnectCall(call);
                return;
            }

            InternalCallEndpointSession session = new InternalCallEndpointSession(call,
                    callEndpoint, this);
            CallEndpointSessionTracker tracker = new CallEndpointSessionTracker(session,
                    CallEndpointSession.ANSWER_REQUEST, videoState);
            // Set this before bind to streaming app to guarantee audio route changed in time.
            call.setActiveCallEndpoint(callEndpoint);
            mCallsManager.answerCall(call, videoState);
            mInCallController.requestStreamingCall(tracker);
            mSessionsByCall.put(call, tracker);

            tracker.getHandler().postDelayed(new Runnable("CEC.rAC", mTelecomLock) {
                @Override
                public void loggedRun() {
                    // Check if the request have been handled. If not, notify timeout to the
                    // endpoint and dialer call.
                    if (!tracker.isRequestHandled()) {
                        onCallEndpointSessionActivationTimedout(call);
                        call.onAnswerFailed(callEndpoint, ANSWER_FAILED_ENDPOINT_TIMEOUT);
                        mCallsManager.disconnectCall(call);
                    }
                }
            }.prepare(), ENDPOINT_TIMEOUT);
        }
    }

    public void requestPushCall(Call call, CallEndpoint callEndpoint) {
        synchronized (mLock) {
            if (call.getState() != STATE_ACTIVE) {
                // ignore
                return;
            }
            if (!mCallEndpoints.contains(callEndpoint)) {
                // unavailable call endpoint
                Log.i(this, "requestPushCall failed due to unavailable callEndpoint: "
                        + callEndpoint);
                call.onPushFailed(callEndpoint, PUSH_FAILED_ENDPOINT_UNAVAILABLE);
                mCallsManager.disconnectCall(call);
                return;
            }

            InternalCallEndpointSession session = new InternalCallEndpointSession(call,
                    callEndpoint, this);
            CallEndpointSessionTracker tracker = new CallEndpointSessionTracker(session,
                    CallEndpointSession.PUSH_REQUEST);
            call.setActiveCallEndpoint(callEndpoint);
            mSessionsByCall.put(call, tracker);
            mInCallController.requestStreamingCall(tracker);

            tracker.getHandler().postDelayed(new Runnable("CEC.rPushC", mTelecomLock) {
                @Override
                public void loggedRun() {
                    // Check if the request have been handled. If not, notify timeout to the
                    // endpoint and dialer call.
                    if (!tracker.isRequestHandled()) {
                        onCallEndpointSessionActivationTimedout(call);
                        call.onPushFailed(callEndpoint, PUSH_FAILED_ENDPOINT_TIMEOUT);
                        mCallsManager.disconnectCall(call);
                    }
                }
            }.prepare(), ENDPOINT_TIMEOUT);
        }
    }

    public Set<CallEndpoint> getCallEndpoints() {
        synchronized (mLock) {
            return Collections.unmodifiableSet(mCallEndpoints);
        }
    }

    public CallsManager getCallsManager() {
        return mCallsManager;
    }

    @VisibleForTesting
    public SystemStateHelper.SystemStateListener getSystemStateListener() {
        return mSystemStateListener;
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (oldState != newState && newState == CallState.DISCONNECTED) {
            synchronized (mLock) {
                if (mSessionsByCall.remove(call) != null) {
                    Log.i(this, "Stop track call endpoint session of disconnected call %s",
                            call);
                }
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        synchronized (mLock) {
            if (mSessionsByCall.remove(call) != null) {
                Log.i(this, "Stop track call endpoint session of removed call %s",
                        call);
            }
        }
    }

    public void onCallEndpointSessionActivationFailed(Call call, int reason) {
        synchronized (mLock) {
            CallEndpointSessionTracker tracker = mSessionsByCall.get(call);
            if (tracker != null) {
                CallEndpoint callEndpoint = tracker.getCallEndpoint();
                tracker.setRequestHandled(true);
                call.setActiveCallEndpoint(null);
                if (reason == android.telecom.CallEndpointSession.ACTIVATION_FAILURE_UNAVAILABLE) {
                    Log.i(this, "Call endpoint session activation failed due to " +
                            "unavailable call endpoint: %s", callEndpoint);

                    if (tracker.getRequestType() == CallEndpointSession.ANSWER_REQUEST) {
                        call.onAnswerFailed(callEndpoint, ANSWER_FAILED_ENDPOINT_UNAVAILABLE);
                    } else if (tracker.getRequestType() == CallEndpointSession.PUSH_REQUEST) {
                        call.onPushFailed(callEndpoint, PUSH_FAILED_ENDPOINT_UNAVAILABLE);
                    }

                    mCallsManager.disconnectCall(call);
                } else if (reason
                        == android.telecom.CallEndpointSession.ACTIVATION_FAILURE_REJECTED) {
                    Log.i(this, "Call endpoint session activation failed due to " +
                            "call endpoint rejection: %s", callEndpoint);
                    if (tracker.getRequestType() == CallEndpointSession.ANSWER_REQUEST) {
                        call.onAnswerFailed(callEndpoint, ANSWER_FAILED_ENDPOINT_REJECTED);
                    } else if (tracker.getRequestType() == CallEndpointSession.PUSH_REQUEST) {
                        call.onPushFailed(callEndpoint, PUSH_FAILED_ENDPOINT_REJECTED);
                    }

                    mCallsManager.disconnectCall(call);
                } else {
                    // Unexpected
                    Log.i(this, "Call endpoint session activation failed due to unknown reason %s",
                            reason);
                    if (tracker.getRequestType() == CallEndpointSession.ANSWER_REQUEST) {
                        call.onAnswerFailed(callEndpoint, ANSWER_FAILED_UNKNOWN_REASON);
                    } else if (tracker.getRequestType() == CallEndpointSession.PUSH_REQUEST) {
                        call.onPushFailed(callEndpoint,
                                PUSH_FAILED_UNKNOWN_REASON);
                    }

                    mCallsManager.disconnectCall(call);
                }
            } else {
                Log.w(this, "onCallEndpointSessionActivationFailed, unknown call %s", call);
            }
        }
    }

    public void onCallEndpointSessionActivated(Call call) {
        synchronized (mLock) {
            CallEndpointSessionTracker tracker = mSessionsByCall.get(call);
            if (tracker != null) {
                tracker.setRequestHandled(true);
                mCallsManager.getInCallController().unbindFromDialer();
                call.setTethered(true);
                call.setConnectionCapabilities(call.getConnectionCapabilities()
                        | Connection.CAPABILITY_CAN_PULL_CALL);
            } else {
                Log.w(this, "onCallEndpointSessionActivated, unknown call %s", call);
            }
        }
    }

    public void onCallEndpointSessionDeactivated(Call call) {
        synchronized (mLock) {
            CallEndpointSessionTracker tracker = mSessionsByCall.get(call);
            if (tracker != null) {
                tracker.setRequestHandled(true);
                call.setActiveCallEndpoint(null);
                notifySessionDeactivated(call);
                mCallsManager.disconnectCall(call);
            } else {
                Log.w(this, "onCallEndpointSessionDeactivated, unknown call %s", call);
            }
        }
    }

    public void onCallEndpointSessionActivationTimedout(Call call) {
        synchronized (mLock) {
            CallEndpointSessionTracker tracker = mSessionsByCall.get(call);
            if (tracker != null) {
                CallEndpoint callEndpoint = tracker.getCallEndpoint();
                Log.i(this, "Call endpoint session activation request %s failed due to timeout",
                        tracker.getRequestType());
                if (tracker.getRequestType() == CallEndpointSession.ANSWER_REQUEST) {
                    call.onAnswerFailed(callEndpoint, ANSWER_FAILED_ENDPOINT_TIMEOUT);
                } else if (tracker.getRequestType() == CallEndpointSession.PUSH_REQUEST) {
                    call.onPushFailed(callEndpoint, PUSH_FAILED_ENDPOINT_TIMEOUT);
                }

                if (tracker.getCallEndpointCallback() != null) {
                    try {
                        tracker.getCallEndpointCallback().onCallEndpointSessionActivationTimeout();
                    } catch (RemoteException e) {
                        Log.e(this, e, "Failed to notify call endpoint session activation timeout");
                    }
                }

                mCallsManager.disconnectCall(call);
            } else {
                Log.w(this, "onCallEndpointSessionActivationTimedout, unknown call %s", call);
            }
        }
    }


    public void notifySessionDeactivated(Call call) {
        synchronized (mLock) {
            CallEndpointSessionTracker tracker = mSessionsByCall.get(call);
            if (tracker != null) {
                try {
                    tracker.getCallEndpointCallback().onCallEndpointSessionDeactivated();
                } catch (RemoteException e) {
                    Log.e(this, e, "Failed to notify call endpoint session deactivation");
                }
            } else {
                Log.w(this, "notifySessionDeactivated, unknown call %s", call);
            }
        }
    }

    @VisibleForTesting
    public CallEndpointSessionTracker getSessionTrackerByCall(Call call) {
        return mSessionsByCall.get(call);
    }
}
