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

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallException;
import android.telecom.Connection;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.callsequencing.voip.OutgoingCallTransaction;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.R;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction layer for CallsManager to perform call sequencing operations through CallsManager
 * or CallSequencingController, which is controlled by {@link FeatureFlags#enableCallSequencing()}.
 */
public class CallsManagerCallSequencingAdapter {

    private final CallsManager mCallsManager;
    private final Context mContext;
    private final CallSequencingController mSequencingController;
    private final CallAudioManager mCallAudioManager;
    private final Handler mHandler;
    private final FeatureFlags mFeatureFlags;
    private final boolean mIsCallSequencingEnabled;

    public CallsManagerCallSequencingAdapter(CallsManager callsManager, Context context,
            CallSequencingController sequencingController, CallAudioManager callAudioManager,
            FeatureFlags featureFlags) {
        mCallsManager = callsManager;
        mContext = context;
        mSequencingController = sequencingController;
        mCallAudioManager = callAudioManager;
        mHandler = sequencingController.getHandler();
        mFeatureFlags = featureFlags;
        mIsCallSequencingEnabled = featureFlags.enableCallSequencing();
    }

    /**
     * Conditionally try to answer the call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param incomingCall The incoming call that should be answered.
     * @param videoState The video state configuration associated with the call.
     * @param requestOrigin The origin of the request.
     */
    public void answerCall(Call incomingCall, int videoState,
            @CallsManager.RequestOrigin int requestOrigin) {
        if (mIsCallSequencingEnabled && !incomingCall.isTransactionalCall()) {
            mSequencingController.answerCall(incomingCall, videoState, requestOrigin);
        } else {
            mCallsManager.answerCallOld(incomingCall, videoState, requestOrigin);
        }
    }

    /**
     * Conditionally attempt to unhold the provided call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param call The call to unhold.
     */
    public void unholdCall(Call call) {
        if (mIsCallSequencingEnabled) {
            mSequencingController.unholdCall(call);
        } else {
            mCallsManager.unholdCallOld(call);
        }
    }

    /**
     * Conditionally attempt to hold the provided call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param call The call to hold.
     */
    public void holdCall(Call call) {
        // Sequencing already taken care of for CSW/TSW in Call class.
        CompletableFuture<Boolean> holdFuture = call.hold();
        maybeLogFutureResultTransaction(holdFuture, "holdCall", "CMCSA.hC",
                "hold call transaction succeeded.", "hold call transaction failed.");
    }

    /**
     * Conditionally disconnect the provided call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled. The sequencing functionality ensures that we wait for
     * the call to be disconnected as signalled by CSW/TSW as to ensure that subsequent call
     * operations don't overlap with this one.
     * @param call The call to disconnect.
     */
    public void disconnectCall(Call call) {
        int previousState = call.getState();
        if (mIsCallSequencingEnabled) {
            mSequencingController.disconnectCall(call, previousState);
        } else {
            mCallsManager.disconnectCallOld(call, previousState);
        }
    }

    /**
     * Conditionally make room for the outgoing call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param isEmergency Indicator of whether the call is an emergency call.
     * @param call The call to potentially make room for.
     * @return {@link CompletableFuture} which will contain the result of the transaction if room
     *         was able to made for the call.
     */
    public CompletableFuture<Boolean> makeRoomForOutgoingCall(boolean isEmergency, Call call) {
        if (mIsCallSequencingEnabled) {
            return mSequencingController.makeRoomForOutgoingCall(isEmergency, call);
        } else {
            return isEmergency
                    ? CompletableFuture.completedFuture(
                            mCallsManager.makeRoomForOutgoingEmergencyCall(call))
                    : CompletableFuture.completedFuture(
                            mCallsManager.makeRoomForOutgoingCall(call));
        }
    }

    /**
     * Attempts to mark the self-managed call as active by first holding the active call and then
     * requesting call focus for the self-managed call.
     * @param call The self-managed call to set active
     */
    public void markCallAsActiveSelfManagedCall(Call call) {
        if (mIsCallSequencingEnabled) {
            mSequencingController.handleSetSelfManagedCallActive(call);
        } else {
            mCallsManager.holdActiveCallForNewCall(call);
            mCallsManager.requestActionSetActiveCall(call,
                    "active set explicitly for self-managed");
        }
    }

    /**
     * Helps create the transaction representing the outgoing transactional call. For outgoing
     * calls, there can be more than one transaction that will need to complete when
     * mIsCallSequencingEnabled is true. Otherwise, rely on the old behavior of creating an
     * {@link OutgoingCallTransaction}.
     * @param callAttributes The call attributes associated with the call.
     * @param extras The extras that are associated with the call.
     * @param callingPackage The calling package representing where the request was invoked from.
     * @return The {@link CompletableFuture<CallTransaction>} that encompasses the request to
     *         place/receive the transactional call.
     */
    public CompletableFuture<CallTransaction> createTransactionalOutgoingCall(String callId,
            CallAttributes callAttributes, Bundle extras, String callingPackage) {
        return mIsCallSequencingEnabled
                ? mSequencingController.createTransactionalOutgoingCall(callId,
                callAttributes, extras, callingPackage)
                : CompletableFuture.completedFuture(new OutgoingCallTransaction(callId,
                        mCallsManager.getContext(), callAttributes, mCallsManager, extras,
                        mFeatureFlags));
    }

    /**
     * attempt to hold or swap the current active call in favor of a new call request. The
     * OutcomeReceiver will return onResult if the current active call is held or disconnected.
     * Otherwise, the OutcomeReceiver will fail.
     * @param newCall The new (transactional) call that's waiting to go active.
     * @param isCallControlRequest Indication of whether this is a call control request.
     * @param callback The callback to report the result of the aforementioned hold
     *      transaction.
     */
    public void transactionHoldPotentialActiveCallForNewCall(Call newCall,
            boolean isCallControlRequest, OutcomeReceiver<Boolean, CallException> callback) {
        String mTag = "transactionHoldPotentialActiveCallForNewCall: ";
        Call activeCall = (Call) mCallsManager.getConnectionServiceFocusManager()
                .getCurrentFocusCall();
        Log.i(this, mTag + "newCall=[%s], activeCall=[%s]", newCall, activeCall);

        if (activeCall == null || activeCall == newCall) {
            Log.i(this, mTag + "no need to hold activeCall");
            callback.onResult(true);
            return;
        }

        if (mFeatureFlags.transactionalHoldDisconnectsUnholdable()) {
            // prevent bad actors from disconnecting the activeCall. Instead, clients will need to
            // notify the user that they need to disconnect the ongoing call before making the
            // new call ACTIVE.
            if (isCallControlRequest
                    && !mCallsManager.canHoldOrSwapActiveCall(activeCall, newCall)) {
                Log.i(this, mTag + "CallControlRequest exit");
                callback.onError(new CallException("activeCall is NOT holdable or swappable, please"
                        + " request the user disconnect the call.",
                        CallException.CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL));
                return;
            }

            if (mIsCallSequencingEnabled) {
                mSequencingController.transactionHoldPotentialActiveCallForNewCallSequencing(
                        newCall, callback);
            } else {
                // The code path without sequencing but where transactionalHoldDisconnectsUnholdable
                // flag is enabled.
                mCallsManager.transactionHoldPotentialActiveCallForNewCallOld(newCall,
                        activeCall, callback);
            }
        } else {
            // The unflagged path (aka original code with no flags).
            mCallsManager.transactionHoldPotentialActiveCallForNewCallUnflagged(activeCall,
                    newCall, callback);
        }
    }

    /**
     * Attempts to move the held call to the foreground in cases where we need to auto-unhold the
     * call.
     */
    public void maybeMoveHeldCallToForeground(Call removedCall, boolean isLocallyDisconnecting) {
        CompletableFuture<Boolean> unholdForegroundCallFuture = null;
        Call foregroundCall = mCallAudioManager.getPossiblyHeldForegroundCall();
        // There are some cases (non-holdable calls) where we may want to skip auto-unholding when
        // we're processing a new outgoing call and waiting for it to go active. Skip the
        // auto-unholding in this case so that we don't end up with two active calls. If the new
        // call fails, we will auto-unhold on that removed call. This is only set in
        // CallSequencingController because the legacy code doesn't wait for disconnects to occur
        // in order to place an outgoing (emergency) call, so we don't see this issue.
        if (removedCall.getSkipAutoUnhold()) {
            return;
        }

        if (isLocallyDisconnecting) {
            boolean isDisconnectingChildCall = removedCall.isDisconnectingChildCall();
            Log.v(this, "maybeMoveHeldCallToForeground: isDisconnectingChildCall = "
                    + isDisconnectingChildCall + "call -> %s", removedCall);
            // Auto-unhold the foreground call due to a locally disconnected call, except if the
            // call which was disconnected is a member of a conference (don't want to auto
            // un-hold the conference if we remove a member of the conference).
            // Also, ensure that the call we're removing is from the same ConnectionService as
            // the one we're removing.  We don't want to auto-unhold between ConnectionService
            // implementations, especially if one is managed and the other is a VoIP CS.
            if (!isDisconnectingChildCall && foregroundCall != null
                    && foregroundCall.getState() == CallState.ON_HOLD
                    && CallsManager.areFromSameSource(foregroundCall, removedCall)) {
                unholdForegroundCallFuture = foregroundCall.unhold();
            }
        } else if (foregroundCall != null &&
                !foregroundCall.can(Connection.CAPABILITY_SUPPORT_HOLD) &&
                foregroundCall.getState() == CallState.ON_HOLD) {

            // The new foreground call is on hold, however the carrier does not display the hold
            // button in the UI.  Therefore, we need to auto unhold the held call since the user
            // has no means of unholding it themselves.
            Log.i(this, "maybeMoveHeldCallToForeground: Auto-unholding held foreground call (call "
                    + "doesn't support hold)");
            unholdForegroundCallFuture = foregroundCall.unhold();
        }
        maybeLogFutureResultTransaction(unholdForegroundCallFuture,
                "maybeMoveHeldCallToForeground", "CM.mMHCTF",
                "Successfully unheld the foreground call.",
                "Failed to unhold the foreground call.");
    }

    /**
     * Generic helper to log the result of the {@link CompletableFuture} containing the transactions
     * that are being processed in the context of call sequencing.
     * @param future The {@link CompletableFuture} encompassing the transaction that's being
     *               computed.
     * @param methodName The method name to describe the type of transaction being processed.
     * @param sessionName The session name to identify the log.
     * @param successMsg The message to be logged if the transaction succeeds.
     * @param failureMsg The message to be logged if the transaction fails.
     */
    public void maybeLogFutureResultTransaction(CompletableFuture<Boolean> future,
            String methodName, String sessionName, String successMsg, String failureMsg) {
        if (mIsCallSequencingEnabled && future != null) {
            mSequencingController.logFutureResultTransaction(future, methodName, sessionName,
                    successMsg, failureMsg);
        }
    }

    /**
     * Determines if we need to add the {@link Connection#EXTRA_ANSWERING_DROPS_FG_CALL} extra to
     * the incoming connection. This is set if the ongoing calls don't support hold.
     */
    public void maybeAddAnsweringCallDropsFg(Call activeCall, Call incomingCall) {
        if (mIsCallSequencingEnabled) {
            mSequencingController.maybeAddAnsweringCallDropsFg(activeCall, incomingCall);
        } else {
            mCallsManager.maybeAddAnsweringCallDropsFgOld(activeCall, incomingCall);
        }
    }

    /**
     * Tries to see if there are any ongoing calls on another phone account when an MMI code is
     * detected to determine whether it should be allowed. For DSDA purposes, we will not allow any
     * MMI codes when there's a call on a different phone account.
     * @param call The call to ignore and the associated phone account to exclude when getting the
     *             total call count.
     * @return {@code true} if the MMI code should be allowed, {@code false} otherwise.
     */
    public boolean shouldAllowMmiCode(Call call) {
        return !mIsCallSequencingEnabled || !mSequencingController.hasMmiCodeRestriction(call);
    }

    /**
     * Processes the simultaneous call type for the ongoing calls that are being tracked in
     * {@link CallsManager}. The current call's simultaneous call type will be overridden only if
     * it's current type priority is lower than the one being set.
     * @param calls The list of the currently tracked calls.
     */
    public void processSimultaneousCallTypes(Collection<Call> calls) {
        // Metrics should only be tracked when call sequencing flag is enabled.
        if (!mIsCallSequencingEnabled) {
            return;
        }
        // Device should have simultaneous calling supported.
        boolean isSimultaneousCallingSupported = mCallsManager.isDsdaCallingPossible();
        int type;
        // Go through the available calls' phone accounts to determine how many different ones
        // are being used.
        Set<PhoneAccountHandle> handles = new HashSet<>();
        for (Call call : calls) {
            if (call.getTargetPhoneAccount() != null) {
                handles.add(call.getTargetPhoneAccount());
            }
            // No need to proceed further given that we already know there is more than 1 phone
            // account being used.
            if (handles.size() > 1) {
                break;
            }
        }
        type = handles.size() > 1
                ? (isSimultaneousCallingSupported ? Call.CALL_DIRECTION_DUAL_DIFF_ACCOUNT
                        : Call.CALL_SIMULTANEOUS_DISABLED_DIFF_ACCOUNT)
                : (isSimultaneousCallingSupported ? Call.CALL_DIRECTION_DUAL_SAME_ACCOUNT
                        : Call.CALL_SIMULTANEOUS_DISABLED_SAME_ACCOUNT);

        Log.i(this, "processSimultaneousCallTypes: the calculated simultaneous call type for "
                + "the tracked calls is [%d]", type);
        calls.forEach(c -> {
            // If the current call's simultaneous call type priority is lower than the one being
            // set, then let the override occur. Otherwise, ignore it.
            if (c.getSimultaneousType() < type) {
                Log.i(this, "processSimultaneousCallTypes: overriding simultaneous call type for "
                        + "call (%s). Previous value: %d", c.getId(), c.getSimultaneousType());
                c.setSimultaneousType(type);
            }
        });
    }

    /**
     * Upon a call resume failure, we will auto-unhold the foreground call that was held. Note that
     * this should only apply for calls across phone accounts as the ImsPhoneCallTracker handles
     * this for a single phone.
     * @param callResumeFailed The call that failed to resume.
     * @param callToUnhold The fg call that was held.
     */
    public void handleCallResumeFailed(Call callResumeFailed, Call callToUnhold) {
        if (mIsCallSequencingEnabled && !mSequencingController.arePhoneAccountsSame(
                callResumeFailed, callToUnhold)) {
            unholdCall(callToUnhold);
        }
    }

    public Handler getHandler() {
        return mHandler;
    }
}
