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

import static android.Manifest.permission.CALL_PRIVILEGED;

import static com.android.server.telecom.CallsManager.CALL_FILTER_ALL;
import static com.android.server.telecom.CallsManager.LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_MSG;
import static com.android.server.telecom.CallsManager.LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_UUID;
import static com.android.server.telecom.CallsManager.LIVE_CALL_STUCK_CONNECTING_ERROR_MSG;
import static com.android.server.telecom.CallsManager.LIVE_CALL_STUCK_CONNECTING_ERROR_UUID;
import static com.android.server.telecom.CallsManager.ONGOING_CALL_STATES;
import static com.android.server.telecom.CallsManager.OUTGOING_CALL_STATES;
import static com.android.server.telecom.UserUtil.showErrorDialogForRestrictedOutgoingCall;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.OutcomeReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallException;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.AnomalyReporterAdapter;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.LogUtils;
import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.MmiUtils;
import com.android.server.telecom.R;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.callsequencing.voip.OutgoingCallTransaction;
import com.android.server.telecom.callsequencing.voip.OutgoingCallTransactionSequencing;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.metrics.ErrorStats;
import com.android.server.telecom.metrics.TelecomMetricsController;
import com.android.server.telecom.stats.CallFailureCause;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Controls the sequencing between calls when moving between the user ACTIVE (RINGING/ACTIVE) and
 * user INACTIVE (INCOMING/HOLD/DISCONNECTED) states. This controller is gated by the
 * {@link FeatureFlags#enableCallSequencing()} flag. Call state changes are verified on a
 * transactional basis where each operation is verified step by step for cross-phone account calls
 * or just for the focus call in the case of processing calls on the same phone account.
 */
public class CallSequencingController {
    private final CallsManager mCallsManager;
    private final ClockProxy mClockProxy;
    private final AnomalyReporterAdapter mAnomalyReporter;
    private final Timeouts.Adapter mTimeoutsAdapter;
    private final TelecomMetricsController mMetricsController;
    private final Handler mHandler;
    private final Context mContext;
    private final MmiUtils mMmiUtils;
    private final FeatureFlags mFeatureFlags;
    private static String TAG = CallSequencingController.class.getSimpleName();
    public static final UUID SEQUENCING_CANNOT_HOLD_ACTIVE_CALL_UUID =
            UUID.fromString("ea094d77-6ea9-4e40-891e-14bff5d485d7");
    public static final String SEQUENCING_CANNOT_HOLD_ACTIVE_CALL_MSG =
            "Cannot hold active call";

    public CallSequencingController(CallsManager callsManager, Context context,
            ClockProxy clockProxy, AnomalyReporterAdapter anomalyReporter,
            Timeouts.Adapter timeoutsAdapter, TelecomMetricsController metricsController,
            MmiUtils mmiUtils, FeatureFlags featureFlags) {
        mCallsManager = callsManager;
        mClockProxy = clockProxy;
        mAnomalyReporter = anomalyReporter;
        mMetricsController = metricsController;
        mTimeoutsAdapter = timeoutsAdapter;
        HandlerThread handlerThread = new HandlerThread(this.toString());
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mMmiUtils = mmiUtils;
        mFeatureFlags = featureFlags;
        mContext = context;
    }

    /**
     * Creates the outgoing call transaction given that call sequencing is enabled. Two separate
     * transactions are being tracked here; one is if room needs to be made for the outgoing call
     * and another to verify that the new call was placed. We need to ensure that the transaction
     * to make room for the outgoing call is processed beforehand (i.e. see
     * {@link OutgoingCallTransaction}.
     * @param callAttributes The call attributes associated with the call.
     * @param extras The extras that are associated with the call.
     * @param callingPackage The calling package representing where the request was invoked from.
     * @return The {@link CompletableFuture<CallTransaction>} that encompasses the request to
     *         place/receive the transactional call.
     */
    public CompletableFuture<CallTransaction> createTransactionalOutgoingCall(String callId,
            CallAttributes callAttributes, Bundle extras, String callingPackage) {
        PhoneAccountHandle requestedAccountHandle = callAttributes.getPhoneAccountHandle();
        Uri address = callAttributes.getAddress();
        if (mCallsManager.isOutgoingCallPermitted(requestedAccountHandle)) {
            Log.d(this, "createTransactionalOutgoingCall: outgoing call permitted");
            final boolean hasCallPrivilegedPermission = mContext.checkCallingPermission(
                    CALL_PRIVILEGED) == PackageManager.PERMISSION_GRANTED;

            final Intent intent = new Intent(hasCallPrivilegedPermission ?
                    Intent.ACTION_CALL_PRIVILEGED : Intent.ACTION_CALL, address);
            Bundle updatedExtras = OutgoingCallTransaction.generateExtras(callId, extras,
                    callAttributes, mFeatureFlags);
            // Note that this may start a potential transaction to make room for the outgoing call
            // so we want to ensure that transaction is queued up first and then create another
            // transaction to complete the call future.
            CompletableFuture<Call> callFuture = mCallsManager.startOutgoingCall(address,
                    requestedAccountHandle, updatedExtras, requestedAccountHandle.getUserHandle(),
                    intent, callingPackage);
            // The second transaction is represented below which will contain the result of whether
            // the new outgoing call was placed or not. To simplify the logic, we will wait on the
            // result of the outgoing call future before adding the transaction so that we can wait
            // for the make room future to complete first.
            if (callFuture == null) {
                Log.d(this, "createTransactionalOutgoingCall: Outgoing call not permitted at the "
                        + "current time.");
                return CompletableFuture.completedFuture(new OutgoingCallTransactionSequencing(
                        mCallsManager, null, true /* callNotPermitted */, mFeatureFlags));
            }
            return callFuture.thenComposeAsync((call) -> CompletableFuture.completedFuture(
                    new OutgoingCallTransactionSequencing(mCallsManager, callFuture,
                            false /* callNotPermitted */, mFeatureFlags)),
                    new LoggedHandlerExecutor(mHandler, "CSC.aC", mCallsManager.getLock()));
        } else {
            Log.d(this, "createTransactionalOutgoingCall: outgoing call not permitted at the "
                    + "current time.");
            return CompletableFuture.completedFuture(new OutgoingCallTransactionSequencing(
                    mCallsManager, null, true /* callNotPermitted */, mFeatureFlags));
        }
    }

    /**
     * Processes the answer call request from the app and verifies the call state changes with
     * sequencing provided that the calls that are being manipulated are across phone accounts.
     * @param incomingCall The incoming call to be answered.
     * @param videoState The video state configuration for the provided call.
     */
    public void answerCall(Call incomingCall, int videoState) {
        Log.i(this, "answerCall: Beginning call sequencing transaction for answering "
                + "incoming call.");
        holdActiveCallForNewCallWithSequencing(incomingCall).thenComposeAsync((result) -> {
                if (result) {
                    mCallsManager.requestFocusActionAnswerCall(incomingCall, videoState);
                } else {
                    Log.i(this, "answerCall: Hold active call transaction failed. Aborting "
                            + "request to answer the incoming call.");
                }
                return CompletableFuture.completedFuture(result);
            }, new LoggedHandlerExecutor(mHandler, "CSC.aC",
                mCallsManager.getLock()));
    }

    /**
     * Handles the case of setting a self-managed call active with call sequencing support.
     * @param call The self-managed call that's waiting to go active.
     */
    public void handleSetSelfManagedCallActive(Call call) {
        holdActiveCallForNewCallWithSequencing(call).thenComposeAsync((result) -> {
                if (result) {
                    Log.i(this, "markCallAsActive: requesting focus for self managed call "
                            + "before setting active.");
                    mCallsManager.requestActionSetActiveCall(call,
                            "active set explicitly for self-managed");
                } else {
                    Log.i(this, "markCallAsActive: Unable to hold active call. "
                            + "Aborting transaction to set self managed call active.");
                }
                return CompletableFuture.completedFuture(result);
            }, new LoggedHandlerExecutor(mHandler,
                "CM.mCAA", mCallsManager.getLock()));
    }

    /**
     * This applies to transactional calls which request to hold the active call with call
     * sequencing support. The resulting future is an indication of whether the hold request
     * succeeded which is then used to create additional transactions to request call focus for the
     * new call.
     * @param newCall The new transactional call that's waiting to go active.
     * @param callback The callback used to report the result of holding the active call and if
     *                 the new call can go active.
     * @return The {@code CompletableFuture} indicating the result of holding the active call
     *         (if applicable).
     */
    public void transactionHoldPotentialActiveCallForNewCallSequencing(
            Call newCall, OutcomeReceiver<Boolean, CallException> callback) {
        holdActiveCallForNewCallWithSequencing(newCall)
                .thenComposeAsync((result) -> {
                    if (result) {
                        // Either we were able to hold the active call or the active call was
                        // disconnected in favor of the new call.
                        callback.onResult(true);
                    } else {
                        Log.i(this, "transactionHoldPotentialActiveCallForNewCallSequencing: "
                                + "active call could not be held or disconnected");
                        callback.onError(
                                new CallException("activeCall could not be held or disconnected",
                                CallException.CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL));
                        if (mFeatureFlags.enableCallExceptionAnomReports()) {
                            mAnomalyReporter.reportAnomaly(
                                    SEQUENCING_CANNOT_HOLD_ACTIVE_CALL_UUID,
                                    SEQUENCING_CANNOT_HOLD_ACTIVE_CALL_MSG
                            );
                        }
                    }
                    return CompletableFuture.completedFuture(result);
                }, new LoggedHandlerExecutor(mHandler, "CM.mCAA", mCallsManager.getLock()));
    }

    /**
     * Attempts to hold the active call so that the provided call can go active. This is done via
     * call sequencing and the resulting future is an indication of whether that request
     * has succeeded.
     * @param call The call that's waiting to go active.
     * @return The {@link CompletableFuture} indicating the result of whether the
     *         active call was able to be held (if applicable).
     */
    @VisibleForTesting
    public CompletableFuture<Boolean> holdActiveCallForNewCallWithSequencing(
            Call call) {
        Call activeCall = (Call) mCallsManager.getConnectionServiceFocusManager()
                .getCurrentFocusCall();
        Log.i(this, "holdActiveCallForNewCallWithSequencing, newCall: %s, "
                        + "activeCall: %s", call.getId(),
                (activeCall == null ? "<none>" : activeCall.getId()));
        if (activeCall != null && activeCall != call) {
            boolean isSequencingRequiredActiveAndCall = !arePhoneAccountsSame(call, activeCall);
            if (mCallsManager.canHold(activeCall)) {
                CompletableFuture<Boolean> holdFuture = activeCall.hold("swap to " + call.getId());
                return isSequencingRequiredActiveAndCall
                        ? holdFuture
                        : CompletableFuture.completedFuture(true);
            } else if (mCallsManager.supportsHold(activeCall)) {
                // Handle the case where active call supports hold but can't currently be held.
                // In this case, we'll look for the currently held call to disconnect prior to
                // holding the active call.
                // E.g.
                // Call A - Held   (Supports hold, can't hold)
                // Call B - Active (Supports hold, can't hold)
                // Call C - Incoming
                // Here we need to disconnect A prior to holding B so that C can be answered.
                // This case is driven by telephony requirements ultimately.
                //
                // These cases can further be broken down at the phone account level:
                // E.g. All cases not outlined below...
                // (1)                              (2)
                // Call A (Held) - PA1              Call A (Held) - PA1
                // Call B (Active) - PA2            Call B (Active) - PA2
                // Call C (Incoming) - PA1          Call C (Incoming) - PA2
                // We should ensure that only operations across phone accounts require sequencing.
                // Otherwise, we can send the requests up til the focus call state in question.
                Call heldCall = mCallsManager.getFirstCallWithState(CallState.ON_HOLD);
                CompletableFuture<Boolean> disconnectFutureHandler = null;

                boolean isSequencingRequiredHeldAndActive = false;
                if (heldCall != null) {
                    // If the calls are from the same source or the incoming call isn't a VOIP call
                    // and the held call is a carrier call, then disconnect the held call. The
                    // idea is that if we have a held carrier call and the incoming call is a
                    // VOIP call, we don't want to force the carrier call to auto-disconnect).
                    if (isManagedCall(heldCall) && isVoipCall(call)) {
                        // Otherwise, fail the transaction.
                        return CompletableFuture.completedFuture(false);
                    } else {
                        isSequencingRequiredHeldAndActive = !arePhoneAccountsSame(
                                heldCall, activeCall);
                        disconnectFutureHandler = heldCall.disconnect();
                        Log.i(this, "holdActiveCallForNewCallWithSequencing: "
                                        + "Disconnect held call %s before holding active call %s.",
                                heldCall.getId(), activeCall.getId());
                    }
                }
                Log.i(this, "holdActiveCallForNewCallWithSequencing: Holding active "
                        + "%s before making %s active.", activeCall.getId(), call.getId());

                CompletableFuture<Boolean> holdFutureHandler;
                if (isSequencingRequiredHeldAndActive && disconnectFutureHandler != null) {
                    holdFutureHandler = disconnectFutureHandler
                            .thenComposeAsync((result) -> {
                                if (result) {
                                    return activeCall.hold().thenCompose((holdSuccess) -> {
                                        if (holdSuccess) {
                                            // Increase hold count only if hold succeeds.
                                            call.increaseHeldByThisCallCount();
                                        }
                                        return CompletableFuture.completedFuture(holdSuccess);
                                    });
                                }
                                return CompletableFuture.completedFuture(false);
                            }, new LoggedHandlerExecutor(mHandler,
                                    "CSC.hACFNCWS", mCallsManager.getLock()));
                } else {
                    holdFutureHandler = activeCall.hold();
                    call.increaseHeldByThisCallCount();
                }
                // Next transaction will be performed on the call passed in and the last transaction
                // was performed on the active call so ensure that the caller has this information
                // to determine if sequencing is required.
                return isSequencingRequiredActiveAndCall
                        ? holdFutureHandler
                        : CompletableFuture.completedFuture(true);
            } else {
                // This call does not support hold. If it is from a different connection
                // service or connection manager, then disconnect it, otherwise allow the connection
                // service or connection manager to figure out the right states.
                Log.i(this, "holdActiveCallForNewCallWithSequencing: evaluating disconnecting %s "
                        + "so that %s can be made active.", activeCall.getId(), call.getId());
                if (!activeCall.isEmergencyCall()) {
                    // We don't want to allow VOIP apps to disconnect carrier calls. We are
                    // purposely completing the future with false so that the call isn't
                    // answered.
                    if (isSequencingRequiredActiveAndCall && isVoipCall(call)
                            && isManagedCall(activeCall)) {
                        Log.w(this, "holdActiveCallForNewCallWithSequencing: ignore "
                                + "disconnecting carrier call for making VOIP call active");
                        return CompletableFuture.completedFuture(false);
                    } else {
                        if (isSequencingRequiredActiveAndCall) {
                            // Disconnect all calls with the same phone account as the active call
                            // as they do would not support holding.
                            Log.i(this, "Disconnecting non-holdable calls from account (%s).",
                                    activeCall.getTargetPhoneAccount());
                            return disconnectAllCallsWithPhoneAccount(
                                    activeCall.getTargetPhoneAccount(), false /* excludeAccount */);
                        } else {
                            // Disconnect calls on other phone accounts and allow CS to handle
                            // holding/disconnecting calls from the same CS.
                            Log.i(this, "holdActiveCallForNewCallWithSequencing: "
                                    + "disconnecting calls on other phone accounts and allowing "
                                    + "ConnectionService to determine how to handle this case.");
                            return disconnectAllCallsWithPhoneAccount(
                                    activeCall.getTargetPhoneAccount(), true /* excludeAccount */);
                        }
                    }
                } else {
                    // It's not possible to hold the active call, and it's an emergency call so
                    // we will silently reject the incoming call instead of answering it.
                    Log.w(this, "holdActiveCallForNewCallWithSequencing: rejecting incoming "
                            + "call %s as the active call is an emergency call and "
                            + "it cannot be held.", call.getId());
                    call.reject(false /* rejectWithMessage */, "" /* message */,
                            "active emergency call can't be held");
                    return CompletableFuture.completedFuture(false);
                }
            }
        }
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Processes the unhold call request sent by the app with call sequencing support.
     * @param call The call to be unheld.
     */
    public void unholdCall(Call call) {
        // Cases: set active call on hold and then set this call to active
        // Calls could be made on different phone accounts, in which case, we need to verify state
        // change for each call.
        CompletableFuture<Boolean> unholdCallFutureHandler = null;
        Call activeCall = (Call) mCallsManager.getConnectionServiceFocusManager()
                .getCurrentFocusCall();
        String activeCallId = null;
        boolean isSequencingRequiredActiveAndCall = false;
        if (activeCall != null && !activeCall.isLocallyDisconnecting()) {
            activeCallId = activeCall.getId();
            // Determine whether the calls are placed on different phone accounts.
            isSequencingRequiredActiveAndCall = !arePhoneAccountsSame(activeCall, call);
            boolean canSwapCalls = canSwap(activeCall, call);

            // If the active + held call are from different phone accounts, ensure that the call
            // sequencing states are verified at each step.
            if (canSwapCalls) {
                unholdCallFutureHandler = activeCall.hold("Swap to " + call.getId());
                Log.addEvent(activeCall, LogUtils.Events.SWAP, "To " + call.getId());
                Log.addEvent(call, LogUtils.Events.SWAP, "From " + activeCallId);
            } else {
                if (isSequencingRequiredActiveAndCall) {
                    // If hold isn't supported and the active and held call are on
                    // different phone accounts where the held call is self-managed and active call
                    // is managed, abort the transaction. Otherwise, disconnect the call. We also
                    // don't want to drop an emergency call.
                    if (!activeCall.isEmergencyCall()) {
                        Log.w(this, "unholdCall: Unable to hold the active call (%s),"
                                        + " aborting swap to %s", activeCallId, call.getId(),
                                call.getId());
                        showErrorDialogForCannotHoldCall(call, false);
                    } else {
                        Log.w(this, "unholdCall: %s is an emergency call, aborting swap to %s",
                                activeCallId, call.getId());
                    }
                    return;
                } else {
                    activeCall.hold("Swap to " + call.getId());
                }
            }
        }

        // Verify call state was changed to ACTIVE state
        if (isSequencingRequiredActiveAndCall && unholdCallFutureHandler != null) {
            String fixedActiveCallId = activeCallId;
            // Only attempt to unhold call if previous request to hold/disconnect call (on different
            // phone account) succeeded.
            unholdCallFutureHandler.thenComposeAsync((result) -> {
                if (result) {
                    Log.i(this, "unholdCall: Request to hold active call transaction succeeded.");
                    mCallsManager.requestActionUnholdCall(call, fixedActiveCallId);
                } else {
                    Log.i(this, "unholdCall: Request to hold active call transaction failed. "
                            + "Aborting unhold transaction.");
                }
                return CompletableFuture.completedFuture(result);
            }, new LoggedHandlerExecutor(mHandler, "CSC.uC",
                    mCallsManager.getLock()));
        } else {
            // Otherwise, we should verify call unhold succeeded for focus call.
            mCallsManager.requestActionUnholdCall(call, activeCallId);
        }
    }

    public CompletableFuture<Boolean> makeRoomForOutgoingCall(boolean isEmergency, Call call) {
        return isEmergency
                ? makeRoomForOutgoingEmergencyCall(call)
                : makeRoomForOutgoingCall(call);
    }

    /**
     * This function tries to make room for the new emergency outgoing call via call sequencing.
     * The resulting future is an indication of whether room was able to be made for the emergency
     * call if needed.
     * @param emergencyCall The outgoing emergency call to be placed.
     * @return The {@code CompletableFuture} indicating the result of whether room was able to be
     *         made for the emergency call.
     */
    private CompletableFuture<Boolean> makeRoomForOutgoingEmergencyCall(Call emergencyCall) {
        // Disconnect all self-managed + transactional calls + calls that don't support holding for
        // emergency. We will never use these accounts for emergency calling. For the single sim
        // case (like Verizon), we should support the existing behavior of disconnecting the active
        // call; refrain from disconnecting the held call in this case if it exists.
        Pair<Set<Call>, CompletableFuture<Boolean>> disconnectCallsForEmergencyPair =
                disconnectCallsForEmergencyCall(emergencyCall);
        // The list of calls that were disconnected
        Set<Call> disconnectedCalls = disconnectCallsForEmergencyPair.first;
        // The future encompassing the result of the disconnect transaction(s). Because of the
        // bulk transaction, we will always opt to perform sequencing on this future. Note that this
        // future will always be completed with true if no disconnects occurred.
        CompletableFuture<Boolean> transactionFuture = disconnectCallsForEmergencyPair.second;

        Call ringingCall;
        if (mCallsManager.hasRingingOrSimulatedRingingCall() && !disconnectedCalls
                .contains(mCallsManager.getRingingOrSimulatedRingingCall())) {
            // Always disconnect any ringing/incoming calls when an emergency call is placed to
            // minimize distraction. This does not affect live call count.
            ringingCall = mCallsManager.getRingingOrSimulatedRingingCall();
            ringingCall.getAnalytics().setCallIsAdditional(true);
            ringingCall.getAnalytics().setCallIsInterrupted(true);
            if (ringingCall.getState() == CallState.SIMULATED_RINGING) {
                if (!ringingCall.hasGoneActiveBefore()) {
                    // If this is an incoming call that is currently in SIMULATED_RINGING only
                    // after a call screen, disconnect to make room and mark as missed, since
                    // the user didn't get a chance to accept/reject.
                    transactionFuture = transactionFuture.thenComposeAsync((result) ->
                                    ringingCall.disconnect("emergency call dialed during simulated "
                                            + "ringing after screen."),
                            new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                                    mCallsManager.getLock()));
                } else {
                    // If this is a simulated ringing call after being active and put in
                    // AUDIO_PROCESSING state again, disconnect normally.
                    transactionFuture = transactionFuture.thenComposeAsync((result) ->
                                    ringingCall.reject(false, null,
                                            "emergency call dialed during simulated ringing."),
                            new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                                    mCallsManager.getLock()));
                }
            } else { // normal incoming ringing call.
                // Hang up the ringing call to make room for the emergency call and mark as missed,
                // since the user did not reject.
                ringingCall.setOverrideDisconnectCauseCode(
                        new DisconnectCause(DisconnectCause.MISSED));
                transactionFuture = transactionFuture.thenComposeAsync((result) ->
                                ringingCall.reject(false, null,
                                        "emergency call dialed during ringing."),
                        new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                                mCallsManager.getLock()));
            }
            disconnectedCalls.add(ringingCall);
        } else {
            ringingCall = null;
        }

        // There is already room!
        if (!mCallsManager.hasMaximumLiveCalls(emergencyCall)) {
            return transactionFuture;
        }

        Call liveCall = mCallsManager.getFirstCallWithLiveState();
        Log.i(this, "makeRoomForOutgoingEmergencyCall: call = " + emergencyCall
                + " livecall = " + liveCall);

        // Don't need to proceed further if we already disconnected the live call or if the live
        // call is the emergency call being placed (not likely).
        if (emergencyCall == liveCall || disconnectedCalls.contains(liveCall)) {
            return transactionFuture;
        }

        // After having rejected any potential ringing call as well as calls that aren't supported
        // during emergency calls (refer to disconnectCallsForEmergencyCall logic), we can
        // re-evaluate whether we still have multiple phone accounts in use in order to disconnect
        // non-holdable calls:
        // If (yes) - disconnect call the non-holdable calls (this would be just the active call)
        // If (no)  - skip the disconnect and instead let the logic be handled explicitly for the
        //            single sim behavior.
        boolean areMultiplePhoneAccountsActive = areMultiplePhoneAccountsActive(disconnectedCalls);
        if (areMultiplePhoneAccountsActive && !liveCall.can(Connection.CAPABILITY_SUPPORT_HOLD)) {
            // After disconnecting, we should be able to place the ECC now (we either have no calls
            // or a held call after this point).
            String disconnectReason = "disconnecting non-holdable call to make room "
                    + "for emergency call";
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            return disconnectOngoingCallForEmergencyCall(transactionFuture, liveCall,
                    disconnectReason);
        }

        // If we already disconnected the outgoing call, then don't perform any additional ops on
        // it.
        if (mCallsManager.hasMaximumOutgoingCalls(emergencyCall) && !disconnectedCalls
                .contains(mCallsManager.getFirstCallWithState(OUTGOING_CALL_STATES))) {
            Call outgoingCall = mCallsManager.getFirstCallWithState(OUTGOING_CALL_STATES);
            String disconnectReason = null;
            if (!outgoingCall.isEmergencyCall()) {
                emergencyCall.getAnalytics().setCallIsAdditional(true);
                outgoingCall.getAnalytics().setCallIsInterrupted(true);
                disconnectReason = "Disconnecting dialing call in favor of new dialing"
                        + " emergency call.";
            }
            if (outgoingCall.getState() == CallState.SELECT_PHONE_ACCOUNT) {
                // Correctness check: if there is an orphaned emergency call in the
                // {@link CallState#SELECT_PHONE_ACCOUNT} state, just disconnect it since the user
                // has explicitly started a new call.
                emergencyCall.getAnalytics().setCallIsAdditional(true);
                outgoingCall.getAnalytics().setCallIsInterrupted(true);
                disconnectReason = "Disconnecting call in SELECT_PHONE_ACCOUNT in favor"
                        + " of new outgoing call.";
            }
            if (disconnectReason != null) {
                // Skip auto-unhold for when the outgoing call is disconnected. Consider a scenario
                // where we have a held non-holdable call (VZW) and the dialing call (also VZW). If
                // we auto unhold the VZW while placing the emergency call, then we may end up with
                // two active calls. The auto-unholding logic really only applies for the
                // non-holdable phone account.
                outgoingCall.setSkipAutoUnhold(true);
                boolean isSequencingRequiredRingingAndOutgoing = ringingCall == null
                        || !arePhoneAccountsSame(ringingCall, outgoingCall);
                return disconnectOngoingCallForEmergencyCall(transactionFuture, outgoingCall,
                        disconnectReason);
            }
            //  If the user tries to make two outgoing calls to different emergency call numbers,
            //  we will try to connect the first outgoing call and reject the second.
            emergencyCall.setStartFailCause(CallFailureCause.IN_EMERGENCY_CALL);
            return CompletableFuture.completedFuture(false);
        }

        if (liveCall.getState() == CallState.AUDIO_PROCESSING) {
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            // Skip auto-unhold for when the live call is disconnected. Consider a scenario where
            // we have a held non-holdable call (VZW) and the live call (also VZW) is stuck in
            // audio processing. If we auto unhold the VZW while placing the emergency call, then we
            // may end up with two active calls. The auto-unholding logic really only applies for
            // the non-holdable phone account.
            liveCall.setSkipAutoUnhold(true);
            final String disconnectReason = "disconnecting audio processing call for emergency";
            return disconnectOngoingCallForEmergencyCall(transactionFuture, liveCall,
                    disconnectReason);
        }

        // If the live call is stuck in a connecting state, prompt the user to generate a bugreport.
        if (liveCall.getState() == CallState.CONNECTING) {
            AnomalyReporter.reportAnomaly(LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_UUID,
                    LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_MSG);
        }

        // If we have the max number of held managed calls and we're placing an emergency call,
        // we'll disconnect the active call if it cannot be held. If we have a self-managed call
        // that can't be held, then we should disconnect the call in favor of the emergency call.
        // This will only happen for the single sim scenario to support backwards compatibility.
        // For dual sim, we should try disconnecting the held call and hold the active call. Also
        // note that in a scenario where we don't have any held calls and the live call can't be
        // held (only applies for single sim case), we should try holding the active call (and
        // disconnect on fail) before placing the ECC (i.e. Verizon swap case). The latter is being
        // handled further down in this method.
        Call heldCall = mCallsManager.getFirstCallWithState(CallState.ON_HOLD);
        if (mCallsManager.hasMaximumManagedHoldingCalls(emergencyCall)
                && !disconnectedCalls.contains(heldCall)) {
            final String disconnectReason = "disconnecting to make room for emergency call "
                    + emergencyCall.getId();
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            // Single sim case
            if (!areMultiplePhoneAccountsActive) {
                liveCall.getAnalytics().setCallIsInterrupted(true);
                // Skip auto-unhold for when the live call is disconnected. Consider a scenario
                // where we have a held non-holdable call (VZW) and an active call (also VZW). If
                // we auto unhold the VZW while placing the emergency call, then we may end up with
                // two active calls. The auto-unholding logic really only applies for the
                // non-holdable phone account.
                liveCall.setSkipAutoUnhold(true);
                // Disconnect the active call instead of the holding call because it is historically
                // easier to do, rather than disconnecting a held call and holding the active call.
                return disconnectOngoingCallForEmergencyCall(transactionFuture, liveCall,
                        disconnectReason);
            } else if (heldCall != null) { // Dual sim case
                // Note at this point, we should always have a held call then that should
                // be disconnected (over the active call) but still enforce with a null check and
                // ensure we haven't disconnected it already.
                heldCall.getAnalytics().setCallIsInterrupted(true);
                // Disconnect the held call.
                transactionFuture = disconnectOngoingCallForEmergencyCall(transactionFuture,
                        heldCall, disconnectReason);
            }
        }

        // TODO: Remove once b/23035408 has been corrected.
        // If the live call is a conference, it will not have a target phone account set.  This
        // means the check to see if the live call has the same target phone account as the new
        // call will not cause us to bail early.  As a result, we'll end up holding the
        // ongoing conference call.  However, the ConnectionService is already doing that.  This
        // has caused problems with some carriers.  As a workaround until b/23035408 is
        // corrected, we will try and get the target phone account for one of the conference's
        // children and use that instead.
        PhoneAccountHandle liveCallPhoneAccount = liveCall.getTargetPhoneAccount();
        if (liveCallPhoneAccount == null && liveCall.isConference() &&
                !liveCall.getChildCalls().isEmpty()) {
            liveCallPhoneAccount = mCallsManager.getFirstChildPhoneAccount(liveCall);
            Log.i(this, "makeRoomForOutgoingEmergencyCall: using child call PhoneAccount = " +
                    liveCallPhoneAccount);
        }

        // We may not know which PhoneAccount the emergency call will be placed on yet, but if
        // the liveCall PhoneAccount does not support placing emergency calls, then we know it
        // will not be that one and we do not want multiple PhoneAccounts active during an
        // emergency call if possible. Disconnect the active call in favor of the emergency call
        // instead of trying to hold.
        if (liveCallPhoneAccount != null) {
            PhoneAccount pa = mCallsManager.getPhoneAccountRegistrar().getPhoneAccountUnchecked(
                    liveCallPhoneAccount);
            if((pa.getCapabilities() & PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS) == 0) {
                liveCall.setOverrideDisconnectCauseCode(new DisconnectCause(
                        DisconnectCause.LOCAL, DisconnectCause.REASON_EMERGENCY_CALL_PLACED));
                final String disconnectReason = "outgoing call does not support emergency calls, "
                        + "disconnecting.";
                return disconnectOngoingCallForEmergencyCall(transactionFuture, liveCall,
                        disconnectReason);
            }
        }

        // At this point, if we still have an active call, then it supports holding for emergency
        // and is a managed call. It may not support holding but we will still try to hold anyway
        // (i.e. swap for Verizon). Note that there will only be one call at this stage which is
        // the active call so that means that we will attempt to place the emergency call on the
        // same phone account unless it's not using a Telephony phone account (Fi wifi call), in
        // which case, we would want to verify holding happened. For cases like backup calling, the
        // shared data call will be over Telephony as well as the emergency call, so the shared
        // data call would get disconnected by the CS.

        // We want to verify if the live call was placed via the connection manager. Don't use
        // the manipulated liveCallPhoneAccount since the delegate would pull directly from the
        // target phone account.
        boolean isLiveUsingConnectionManager = !Objects.equals(liveCall.getTargetPhoneAccount(),
                liveCall.getDelegatePhoneAccountHandle());
        return maybeHoldLiveCallForEmergency(transactionFuture, liveCall,
                emergencyCall, isLiveUsingConnectionManager);
    }

    /**
     * This function tries to make room for the new outgoing call via call sequencing. The
     * resulting future is an indication of whether room was able to be made for the call if
     * needed.
     * @param call The outgoing call to make room for.
     * @return The {@code CompletableFuture} indicating the result of whether room was able to be
     *         made for the outgoing call.
     */
    private CompletableFuture<Boolean> makeRoomForOutgoingCall(Call call) {
        // For the purely managed CS cases, check if there's a ringing call, in which case we will
        // disallow the outgoing call.
        if (isManagedCall(call) && mCallsManager.hasManagedRingingOrSimulatedRingingCall()) {
            showErrorDialogForOutgoingDuringRingingCall(call);
            return CompletableFuture.completedFuture(false);
        }
        // Already room!
        if (!mCallsManager.hasMaximumLiveCalls(call)) {
            return CompletableFuture.completedFuture(true);
        }

        // NOTE: If the amount of live calls changes beyond 1, this logic will probably
        // have to change.
        Call liveCall = mCallsManager.getFirstCallWithLiveState();
        Log.i(this, "makeRoomForOutgoingCall call = " + call + " livecall = " +
                liveCall);

        if (call == liveCall) {
            // If the call is already the foreground call, then we are golden.
            // This can happen after the user selects an account in the SELECT_PHONE_ACCOUNT
            // state since the call was already populated into the list.
            return CompletableFuture.completedFuture(true);
        }

        // If the live call is stuck in a connecting state for longer than the transitory timeout,
        // then we should disconnect it in favor of the new outgoing call and prompt the user to
        // generate a bugreport.
        // TODO: In the future we should let the CallAnomalyWatchDog do this disconnection of the
        // live call stuck in the connecting state.  Unfortunately that code will get tripped up by
        // calls that have a longer than expected new outgoing call broadcast response time.  This
        // mitigation is intended to catch calls stuck in a CONNECTING state for a long time that
        // block outgoing calls.  However, if the user dials two calls in quick succession it will
        // result in both calls getting disconnected, which is not optimal.
        if (liveCall.getState() == CallState.CONNECTING
                && ((mClockProxy.elapsedRealtime() - liveCall.getCreationElapsedRealtimeMillis())
                > mTimeoutsAdapter.getNonVoipCallTransitoryStateTimeoutMillis())) {
            if (mFeatureFlags.telecomMetricsSupport()) {
                mMetricsController.getErrorStats().log(ErrorStats.SUB_CALL_MANAGER,
                        ErrorStats.ERROR_STUCK_CONNECTING);
            }
            mAnomalyReporter.reportAnomaly(LIVE_CALL_STUCK_CONNECTING_ERROR_UUID,
                    LIVE_CALL_STUCK_CONNECTING_ERROR_MSG);
            // Skip auto-unhold for when the live call is disconnected. Consider a scenario where
            // we have a held non-holdable call (VZW) and the live call (also VZW) is stuck in
            // connecting. If we auto unhold the VZW while placing the emergency call, then we may
            // end up with two active calls. The auto-unholding logic really only applies for
            // the non-holdable phone account.
            liveCall.setSkipAutoUnhold(true);
            return liveCall.disconnect("Force disconnect CONNECTING call.");
        }

        if (mCallsManager.hasMaximumOutgoingCalls(call)) {
            Call outgoingCall = mCallsManager.getFirstCallWithState(OUTGOING_CALL_STATES);
            if (outgoingCall.getState() == CallState.SELECT_PHONE_ACCOUNT) {
                // If there is an orphaned call in the {@link CallState#SELECT_PHONE_ACCOUNT}
                // state, just disconnect it since the user has explicitly started a new call.
                call.getAnalytics().setCallIsAdditional(true);
                outgoingCall.getAnalytics().setCallIsInterrupted(true);
                // Skip auto-unhold for when the outgoing call is disconnected. Consider a scenario
                // where we have a held non-holdable call (VZW) and a dialing call (also VZW). If we
                // auto unhold the VZW while placing the emergency call, then we may end up with
                // two active calls. The auto-unholding logic really only applies for the
                // non-holdable phone account.
                outgoingCall.setSkipAutoUnhold(true);
                return outgoingCall.disconnect(
                        "Disconnecting call in SELECT_PHONE_ACCOUNT in favor of new "
                                + "outgoing call.");
            }
            showErrorDialogForMaxOutgoingCallOutgoingPresent(call);
            return CompletableFuture.completedFuture(false);
        }

        // If we detect a MMI code, allow it to go through since we are not treating it as an actual
        // call.
        if (mMmiUtils.isPotentialMMICode(call.getHandle())) {
            Log.i(this, "makeRoomForOutgoingCall: Detected mmi code. Allowing to go through.");
            return CompletableFuture.completedFuture(true);
        }

        // Early check to see if we already have a held call + live call. It's possible if a device
        // switches to DSDS with two ongoing calls for the phone account to be null in which case,
        // based on the logic below, we would've completed the future with true and reported a
        // different failure cause. Now, we perform this early check to ensure the right max
        // outgoing call restriction error is displayed instead.
        if (mCallsManager.hasMaximumManagedHoldingCalls(call) && !mCallsManager.canHold(liveCall)) {
            Call heldCall = mCallsManager.getFirstCallWithState(CallState.ON_HOLD);
            showErrorDialogForMaxOutgoingCallTooManyCalls(call,
                    arePhoneAccountsSame(heldCall, liveCall));
            return CompletableFuture.completedFuture(false);
        }

        // Self-Managed + Transactional calls require Telecom to manage calls in the same
        // PhoneAccount, whereas managed calls require the ConnectionService to manage calls in the
        // same PhoneAccount for legacy reasons (Telephony).
        if (arePhoneAccountsSame(call, liveCall) && isManagedCall(call)) {
            Log.i(this, "makeRoomForOutgoingCall: allowing managed CS to handle "
                    + "calls from the same self-managed account");
            return CompletableFuture.completedFuture(true);
        } else if (call.getTargetPhoneAccount() == null) {
            Log.i(this, "makeRoomForOutgoingCall: no PA specified, allowing");
            // Without a phone account, we can't say reliably that the call will fail.
            // If the user chooses the same phone account as the live call, then it's
            // still possible that the call can be made (like with CDMA calls not supporting
            // hold but they still support adding a call by going immediately into conference
            // mode). Return true here and we'll run this code again after user chooses an
            // account.
            return CompletableFuture.completedFuture(true);
        }

        // Try to hold the live call before attempting the new outgoing call.
        if (mCallsManager.canHold(liveCall)) {
            Log.i(this, "makeRoomForOutgoingCall: holding live call.");
            call.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            return liveCall.hold("calling " + call.getId());
        }

        // The live call cannot be held so we're out of luck here.  There's no room.
        showErrorDialogForCannotHoldCall(call, true);
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Processes the request from the app to disconnect a call. This is done via call sequencing
     * so that Telecom properly cleans up the call locally provided that the call has been
     * properly disconnected on the connection side.
     * @param call The call to disconnect.
     * @param previousState The previous state of the call before disconnecting.
     */
    public void disconnectCall(Call call, int previousState) {
        CompletableFuture<Boolean> disconnectFuture = call.disconnect();
        disconnectFuture.thenComposeAsync((result) -> {
            if (result) {
                Log.i(this, "disconnectCall: Disconnect call transaction succeeded. "
                        + "Processing associated cleanup.");
                mCallsManager.processDisconnectCallAndCleanup(call, previousState);
            } else {
                Log.i(this, "disconnectCall: Disconnect call transaction failed. "
                        + "Aborting associated cleanup.");
            }
            return CompletableFuture.completedFuture(false);
        }, new LoggedHandlerExecutor(mHandler, "CSC.dC",
                mCallsManager.getLock()));
    }

    /* HELPERS */

    /* makeRoomForOutgoingEmergencyCall helpers */

    /**
     * Tries to hold the live call before placing the emergency call. If the hold fails, then we
     * will instead disconnect the call. This only applies for when the emergency call and live call
     * are from the same phone account or there's only one ongoing call, in which case, we should
     * place the emergency call on the ongoing call's phone account.
     *
     * Note: This only applies when the live call and emergency call are from the same phone
     * account.
     */
    private CompletableFuture<Boolean> maybeHoldLiveCallForEmergency(
            CompletableFuture<Boolean> transactionFuture,
            Call liveCall, Call emergencyCall, boolean isLiveUsingConnectionManager) {
        emergencyCall.getAnalytics().setCallIsAdditional(true);
        liveCall.getAnalytics().setCallIsInterrupted(true);
        final String holdReason = "calling " + emergencyCall.getId();
        CompletableFuture<Boolean> holdResultFuture;
        holdResultFuture = transactionFuture.thenComposeAsync((result) -> {
            if (result) {
                Log.i(this, "makeRoomForOutgoingEmergencyCall: Previous transaction "
                        + "succeeded. Attempting to hold live call.");
            } else { // Log the failure but proceed with hold transaction.
                Log.i(this, "makeRoomForOutgoingEmergencyCall: Previous transaction "
                        + "failed. Still attempting to hold live call.");
            }
            Log.i(this, "makeRoomForOutgoingEmergencyCall: Attempt to hold live call. "
                    + "Verifying hold: %b", isLiveUsingConnectionManager);
            return liveCall.hold(holdReason);
        }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC", mCallsManager.getLock()));

        // If the live call was placed using a connection manager, we should verify that holding
        // happened before placing the emergency call. We should disconnect the call if hold fails.
        // Otherwise, let Telephony handle additional sequencing that may be required.
        if (!isLiveUsingConnectionManager) {
            return transactionFuture;
        }

        // Otherwise, verify hold succeeded and if it didn't, then hangup the call.
        return holdResultFuture.thenComposeAsync((result) -> {
            if (!result) {
                Log.i(this, "makeRoomForOutgoingEmergencyCall: Attempt to hold live call "
                        + "failed. Disconnecting live call in favor of emergency call.");
                return liveCall.disconnect("Disconnecting live call which failed to be held");
            } else {
                Log.i(this, "makeRoomForOutgoingEmergencyCall: Attempt to hold live call "
                        + "transaction succeeded.");
                emergencyCall.increaseHeldByThisCallCount();
                return CompletableFuture.completedFuture(true);
            }
        }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC", mCallsManager.getLock()));
    }

    /**
     * Disconnects all VOIP (SM + Transactional) as well as those that don't support placing
     * emergency calls before placing an emergency call.
     *
     * Note: If a call can't be held, it will be active to begin with.
     * @return The list of calls to be disconnected alongside the future keeping track of the
     *         disconnect transaction.
     */
    private Pair<Set<Call>, CompletableFuture<Boolean>> disconnectCallsForEmergencyCall(
            Call emergencyCall) {
        Set<Call> callsDisconnected = new HashSet<>();
        Call previousCall = null;
        Call ringingCall = mCallsManager.getRingingOrSimulatedRingingCall();
        CompletableFuture<Boolean> disconnectFuture = CompletableFuture.completedFuture(true);
        for (Call call: mCallsManager.getCalls()) {
            if (skipDisconnectForEmergencyCall(call, ringingCall)) {
                continue;
            }
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            call.getAnalytics().setCallIsInterrupted(true);
            call.setOverrideDisconnectCauseCode(new DisconnectCause(
                    DisconnectCause.LOCAL, DisconnectCause.REASON_EMERGENCY_CALL_PLACED));

            Call finalPreviousCall = previousCall;
            disconnectFuture = disconnectFuture.thenComposeAsync((result) -> {
                if (!result) {
                    // Log the failure if it happens but proceed with the disconnects.
                    Log.i(this, "Call (%s) failed to be disconnected",
                            finalPreviousCall);
                }
                return call.disconnect("Disconnecting call with phone account that does not "
                        + "support emergency call");
            }, new LoggedHandlerExecutor(mHandler, "CSC.dAVC",
                    mCallsManager.getLock()));
            previousCall = call;
            callsDisconnected.add(call);
        }
        return new Pair<>(callsDisconnected, disconnectFuture);
    }

    private boolean skipDisconnectForEmergencyCall(Call call, Call ringingCall) {
        // Conditions for checking if call doesn't need to be disconnected immediately.
        boolean isVoip = isVoipCall(call);
        boolean callSupportsHoldingEmergencyCall = shouldHoldForEmergencyCall(
                call.getTargetPhoneAccount());

        // Skip the ringing call; we'll handle the disconnect explicitly later. Also, if we have
        // a conference call, only disconnect the host call.
        if (call.equals(ringingCall) || call.getParentCall() != null) {
            return true;
        }

        // If the call is managed and supports holding for emergency calls, don't disconnect the
        // call.
        if (!isVoip && callSupportsHoldingEmergencyCall) {
            return true;
        }
        // Otherwise, we will disconnect the call because it doesn't meet one of the conditions
        // above.
        Log.i(this, "Disconnecting call (%s). isManaged: %b, call "
                + "supports holding emergency call: %b", call.getId(), !isVoip,
                callSupportsHoldingEmergencyCall);
        return false;
    }

    /**
     * Waiting on the passed future completion when sequencing is required, this will try to the
     * disconnect the call passed in.
     */
    private CompletableFuture<Boolean> disconnectOngoingCallForEmergencyCall(
            CompletableFuture<Boolean> transactionFuture, Call callToDisconnect,
            String disconnectReason) {
        return transactionFuture.thenComposeAsync((result) -> {
            if (result) {
                Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                        + "previous call succeeded. Attempting to disconnect ongoing call"
                        + " %s.", callToDisconnect);
            } else {
                Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                        + "previous call failed. Still attempting to disconnect ongoing call"
                        + " %s.", callToDisconnect);
            }
            return callToDisconnect.disconnect(disconnectReason);
        }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC", mCallsManager.getLock()));
    }

    /**
     * Determines if DSDA is being used (i.e. calls present on more than one phone account).
     * @param callsToExclude The list of calls to exclude (these will be calls that have been
     *                       disconnected but may still be being tracked by CallsManager depending
     *                       on timing).
     */
    private boolean areMultiplePhoneAccountsActive(Set<Call> callsToExclude) {
        for (Call excludedCall: callsToExclude) {
            Log.i(this, "Calls to exclude: %s", excludedCall);
        }
        List<Call> calls = mCallsManager.getCalls().stream()
                .filter(c -> !callsToExclude.contains(c)).toList();
        PhoneAccountHandle handle1 = null;
        if (!calls.isEmpty()) {
            // Find the first handle different from the one retrieved from the first call in
            // the list.
            for(int i = 0; i < calls.size(); i++) {
                if (handle1 == null && calls.get(i).getTargetPhoneAccount() != null) {
                    handle1 = calls.getFirst().getTargetPhoneAccount();
                }
                if (handle1 != null && calls.get(i).getTargetPhoneAccount() != null
                        && !handle1.equals(calls.get(i).getTargetPhoneAccount())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks the carrier config to see if the carrier supports holding emergency calls.
     * @param handle The {@code PhoneAccountHandle} to check
     * @return {@code true} if the carrier supports holding emergency calls, {@code} false
     *         otherwise.
     */
    private boolean shouldHoldForEmergencyCall(PhoneAccountHandle handle) {
        return mCallsManager.getCarrierConfigForPhoneAccount(handle).getBoolean(
                CarrierConfigManager.KEY_ALLOW_HOLD_CALL_DURING_EMERGENCY_BOOL, true);
    }

    @VisibleForTesting
    public boolean arePhoneAccountsSame(Call call1, Call call2) {
        if (call1 == null || call2 == null) {
            return false;
        }
        return Objects.equals(call1.getTargetPhoneAccount(), call2.getTargetPhoneAccount());
    }

    /**
     * Checks to see if two calls can be swapped. This is granted that the call to be unheld is
     * already ON_HOLD and the active call supports holding. Note that in HoldTracker, there can
     * only be one top call that is holdable (if there are two, the calls are not holdable) and only
     * that connection would have the CAPABILITY_HOLD present. For swapping logic, we should take
     * this into account and request to hold regardless.
     */
    @VisibleForTesting
    private boolean canSwap(Call callToBeHeld, Call callToUnhold) {
        return callToBeHeld.can(Connection.CAPABILITY_SUPPORT_HOLD)
                && callToBeHeld.getState() != CallState.DIALING
                && callToUnhold.getState() == CallState.ON_HOLD;
    }

    private CompletableFuture<Boolean> disconnectAllCallsWithPhoneAccount(
            PhoneAccountHandle handle, boolean excludeAccount) {
        CompletableFuture<Boolean> disconnectFuture = CompletableFuture.completedFuture(true);
        // Filter out the corresponding phone account and ensure that we don't consider conference
        // participants as part of the bulk disconnect (we'll just disconnect the host directly).
        List<Call> calls = mCallsManager.getCalls().stream()
                .filter(c -> excludeAccount != c.getTargetPhoneAccount().equals(handle)
                        && c.getParentCall() == null).toList();
        for (Call call: calls) {
            // Wait for all disconnects before we accept the new call.
            disconnectFuture = disconnectFuture.thenComposeAsync((result) -> {
                if (!result) {
                    Log.i(this, "disconnectAllCallsWithPhoneAccount: "
                            + "Failed to disconnect %s.", call);
                }
                return call.disconnect("Call " + call + " disconnected "
                        + "in favor of new call.");
            }, new LoggedHandlerExecutor(mHandler, "CSC.dACWPA", mCallsManager.getLock()));
        }
        return disconnectFuture;
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
    public void logFutureResultTransaction(CompletableFuture<Boolean> future, String methodName,
            String sessionName, String successMsg, String failureMsg) {
        future.thenApplyAsync((result) -> {
            String msg = methodName + ": " + (result ? successMsg : failureMsg);
            Log.i(this, msg);
            return CompletableFuture.completedFuture(result);
        }, new LoggedHandlerExecutor(mHandler, sessionName, mCallsManager.getLock()));
    }

    public boolean hasMmiCodeRestriction(Call call) {
        if (mCallsManager.getNumCallsWithStateWithoutHandle(
                CALL_FILTER_ALL, call, call.getTargetPhoneAccount(), ONGOING_CALL_STATES) > 0) {
            // Set disconnect cause so that error will be printed out when call is disconnected.
            CharSequence msg = mContext.getText(R.string.callFailed_reject_mmi);
            call.setOverrideDisconnectCauseCode(new DisconnectCause(DisconnectCause.ERROR, msg, msg,
                    "Rejected MMI code due to an ongoing call on another phone account."));
            return true;
        }
        return false;
    }

    public void maybeAddAnsweringCallDropsFg(Call activeCall, Call incomingCall) {
        // Don't set the extra when we have an incoming self-managed call that would potentially
        // disconnect the active managed call.
        if (activeCall == null || (isVoipCall(incomingCall) && isManagedCall(activeCall))) {
            return;
        }
        // Check if the active call doesn't support hold. If it doesn't we should indicate to the
        // user via the EXTRA_ANSWERING_DROPS_FG_CALL extra that the call would be dropped by
        // answering the incoming call.
        if (!mCallsManager.supportsHold(activeCall)) {
            CharSequence droppedApp = activeCall.getTargetPhoneAccountLabel();
            Bundle dropCallExtras = new Bundle();
            dropCallExtras.putBoolean(Connection.EXTRA_ANSWERING_DROPS_FG_CALL, true);

            // Include the name of the app which will drop the call.
            dropCallExtras.putCharSequence(
                    Connection.EXTRA_ANSWERING_DROPS_FG_CALL_APP_NAME, droppedApp);
            Log.i(this, "Incoming call will drop %s call.", droppedApp);
            incomingCall.putConnectionServiceExtras(dropCallExtras);
        }
    }

    private void showErrorDialogForMaxOutgoingCallOutgoingPresent(Call call) {
        int resourceId = R.string.callFailed_outgoing_already_present;
        String reason = " there is already another call connecting. Wait for the "
                + "call to be answered or disconnect before placing another call.";
        showErrorDialogForFailedCall(call, CallFailureCause.MAX_OUTGOING_CALLS, resourceId, reason);
    }

    private void showErrorDialogForMaxOutgoingCallTooManyCalls(
            Call call, boolean arePhoneAccountsSame) {
        int resourceId = arePhoneAccountsSame
                ? R.string.callFailed_too_many_calls_include_merge
                : R.string.callFailed_too_many_calls_exclude_merge;
        String reason = " there are two calls already in progress. Disconnect one "
                + "of the calls or merge the calls (if possible).";
        showErrorDialogForFailedCall(call, CallFailureCause.MAX_OUTGOING_CALLS, resourceId, reason);
    }

    private void showErrorDialogForOutgoingDuringRingingCall(Call call) {
        int resourceId = R.string.callFailed_already_ringing;
        String reason = " can't place outgoing call with an unanswered incoming call.";
        showErrorDialogForFailedCall(call, null, resourceId, reason);
    }

    private void showErrorDialogForCannotHoldCall(Call call, boolean setCallFailure) {
        CallFailureCause cause = null;
        if (setCallFailure) {
            cause = CallFailureCause.CANNOT_HOLD_CALL;
        }
        int resourceId = R.string.callFailed_unholdable_call;
        String reason = " unable to hold live call. Disconnect the unholdable call.";
        showErrorDialogForFailedCall(call, cause, resourceId, reason);
    }

    private void showErrorDialogForFailedCall(Call call, CallFailureCause cause, int resourceId,
            String reason) {
        if (cause != null) {
            call.setStartFailCause(cause);
        }
        showErrorDialogForRestrictedOutgoingCall(mContext, resourceId, TAG, reason);
    }

    public Handler getHandler() {
        return mHandler;
    }

    private boolean isVoipCall(Call call) {
        if (call == null) {
            return false;
        }
        return call.isSelfManaged() || call.isTransactionalCall();
    }

    private boolean isManagedCall(Call call) {
        if (call == null) {
            return false;
        }
        return !call.isSelfManaged() && !call.isTransactionalCall() && !call.isExternalCall();
    }
}
