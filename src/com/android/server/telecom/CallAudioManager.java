/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.media.IAudioService;
import android.media.ToneGenerator;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;

public class CallAudioManager extends CallsManagerListenerBase {

    public interface AudioServiceFactory {
        IAudioService getAudioService();
    }

    private final String LOG_TAG = CallAudioManager.class.getSimpleName();

    private final LinkedHashSet<Call> mActiveOrDialingCalls;
    private final LinkedHashSet<Call> mRingingCalls;
    private final LinkedHashSet<Call> mHoldingCalls;
    private final Set<Call> mCalls;
    private final SparseArray<LinkedHashSet<Call>> mCallStateToCalls;

    private final CallAudioRouteStateMachine mCallAudioRouteStateMachine;
    private final CallAudioModeStateMachine mCallAudioModeStateMachine;
    private final CallsManager mCallsManager;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final Ringer mRinger;
    private final RingbackPlayer mRingbackPlayer;
    private final DtmfLocalTonePlayer mDtmfLocalTonePlayer;

    private Call mForegroundCall;
    private boolean mIsTonePlaying = false;

    public CallAudioManager(CallAudioRouteStateMachine callAudioRouteStateMachine,
            CallsManager callsManager,
            CallAudioModeStateMachine callAudioModeStateMachine,
            InCallTonePlayer.Factory playerFactory,
            Ringer ringer,
            RingbackPlayer ringbackPlayer,
            DtmfLocalTonePlayer dtmfLocalTonePlayer) {
        mActiveOrDialingCalls = new LinkedHashSet<>();
        mRingingCalls = new LinkedHashSet<>();
        mHoldingCalls = new LinkedHashSet<>();
        mCalls = new HashSet<>();
        mCallStateToCalls = new SparseArray<LinkedHashSet<Call>>() {{
            put(CallState.ACTIVE, mActiveOrDialingCalls);
            put(CallState.DIALING, mActiveOrDialingCalls);
            put(CallState.RINGING, mRingingCalls);
            put(CallState.ON_HOLD, mHoldingCalls);
        }};

        mCallAudioRouteStateMachine = callAudioRouteStateMachine;
        mCallAudioModeStateMachine = callAudioModeStateMachine;
        mCallsManager = callsManager;
        mPlayerFactory = playerFactory;
        mRinger = ringer;
        mRingbackPlayer = ringbackPlayer;
        mDtmfLocalTonePlayer = dtmfLocalTonePlayer;

        mPlayerFactory.setCallAudioManager(this);
        mCallAudioModeStateMachine.setCallAudioManager(this);
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (call.getParentCall() != null) {
            // No audio management for calls in a conference.
            return;
        }
        Log.d(LOG_TAG, "Call state changed for TC@%s: %s -> %s", call.getId(),
                CallState.toString(oldState), CallState.toString(newState));

        if (mCallStateToCalls.get(oldState) != null) {
            mCallStateToCalls.get(oldState).remove(call);
        }
        if (mCallStateToCalls.get(newState) != null) {
            mCallStateToCalls.get(newState).add(call);
        }

        updateForegroundCall();
        if (newState == CallState.DISCONNECTED) {
            playToneForDisconnectedCall(call);
        }

        onCallLeavingState(call, oldState);
        onCallEnteringState(call, newState);
    }

    @Override
    public void onCallAdded(Call call) {
        if (call.getParentCall() != null) {
            return; // Don't do audio handling for calls in a conference.
        }

        if (mCalls.contains(call)) {
            Log.w(LOG_TAG, "Call TC@%s is being added twice.", call.getId());
            return; // No guarantees that the same call won't get added twice.
        }

        Log.d(LOG_TAG, "Call added with id TC@%s in state %s", call.getId(),
                CallState.toString(call.getState()));

        if (mCallStateToCalls.get(call.getState()) != null) {
            mCallStateToCalls.get(call.getState()).add(call);
        }
        updateForegroundCall();
        mCalls.add(call);

        onCallEnteringState(call, call.getState());
    }

    @Override
    public void onCallRemoved(Call call) {
        if (call.getParentCall() != null) {
            return; // Don't do audio handling for calls in a conference.
        }

        if (!mCalls.contains(call)) {
            return; // No guarantees that the same call won't get removed twice.
        }

        Log.d(LOG_TAG, "Call removed with id TC@%s in state %s", call.getId(),
                CallState.toString(call.getState()));

        if (mCallStateToCalls.get(call.getState()) != null) {
            mCallStateToCalls.get(call.getState()).remove(call);
        }

        updateForegroundCall();
        mCalls.remove(call);

        onCallLeavingState(call, call.getState());

        if (mCallsManager.getCalls().isEmpty()) {
            Log.v(this, "all calls removed, resetting system audio to default state");
            mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                    CallAudioRouteStateMachine.REINITIALIZE);
        }
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        // This is called after the UI answers the call, but before the connection service
        // sets the call to active. Only thing to handle for mode here is the audio speedup thing.

        if (call.can(android.telecom.Call.Details.CAPABILITY_SPEED_UP_MT_AUDIO)) {
            if (mForegroundCall == call) {
                Log.i(LOG_TAG, "Invoking the MT_AUDIO_SPEEDUP mechanism. Transitioning into " +
                        "an active in-call audio state before connection service has " +
                        "connected the call.");
                if (mCallStateToCalls.get(call.getState()) != null) {
                    mCallStateToCalls.get(call.getState()).remove(call);
                }
                mActiveOrDialingCalls.add(call);
                mCallAudioModeStateMachine.sendMessage(
                        CallAudioModeStateMachine.MT_AUDIO_SPEEDUP_FOR_RINGING_CALL,
                        makeArgsForModeStateMachine());
            }
        }

        if (mRingingCalls.size() == 0) {
            mRinger.stopRinging();
            mRinger.stopCallWaiting();
        }
    }

    @Override
    public void onSessionModifyRequestReceived(Call call, VideoProfile videoProfile) {
        if (videoProfile == null) {
            return;
        }

        if (call != mForegroundCall) {
            // We only play tones for foreground calls.
            return;
        }

        int previousVideoState = call.getVideoState();
        int newVideoState = videoProfile.getVideoState();
        Log.v(this, "onSessionModifyRequestReceived : videoProfile = " + VideoProfile
                .videoStateToString(newVideoState));

        boolean isUpgradeRequest = !VideoProfile.isReceptionEnabled(previousVideoState) &&
                VideoProfile.isReceptionEnabled(newVideoState);

        if (isUpgradeRequest) {
            mPlayerFactory.createPlayer(InCallTonePlayer.TONE_VIDEO_UPGRADE).startTone();
        }
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        if (call != mForegroundCall) {
            return;
        }
        mCallAudioModeStateMachine.sendMessage(
                CallAudioModeStateMachine.FOREGROUND_VOIP_MODE_CHANGE,
                makeArgsForModeStateMachine());
    }

    @Override
    public void onRingbackRequested(Call call, boolean shouldRingback) {
        if (call == mForegroundCall && shouldRingback) {
            mRingbackPlayer.startRingbackForCall(call);
        } else {
            mRingbackPlayer.stopRingbackForCall(call);
        }
    }

    @Override
    public void onIncomingCallRejected(Call call, boolean rejectWithMessage, String message) {
        // This gets called after the UI rejects a call but before the CS processes the rejection.
        // Will get called before the state change from ringing to not ringing.

        if (mRingingCalls.size() == 0 || call == mRingingCalls.iterator().next()) {
            mRinger.stopRinging();
            mRinger.stopCallWaiting();
        }
    }

    @Override
    public void onIsConferencedChanged(Call call) {
        // This indicates a conferencing change, which shouldn't impact any audio mode stuff.
        Call parentCall = call.getParentCall();
        if (parentCall == null) {
            // Indicates that the call should be tracked for audio purposes. Treat it as if it were
            // just added.
            Log.i(LOG_TAG, "Call TC@" + call.getId() + " left conference and will" +
                            " now be tracked by CallAudioManager.");
            onCallAdded(call);
        } else {
            // The call joined a conference, so stop tracking it.
            if (mCallStateToCalls.get(call.getState()) != null) {
                mCallStateToCalls.get(call.getState()).remove(call);
            }

            updateForegroundCall();
            mCalls.remove(call);
        }
    }

    public CallAudioState getCallAudioState() {
        return mCallAudioRouteStateMachine.getCurrentCallAudioState();
    }

    public Call getPossiblyHeldForegroundCall() {
        return mForegroundCall;
    }

    public Call getForegroundCall() {
        if (mForegroundCall != null && mForegroundCall.getState() != CallState.ON_HOLD) {
            return mForegroundCall;
        }
        return null;
    }

    void toggleMute() {
        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                CallAudioRouteStateMachine.TOGGLE_MUTE);
    }

    void mute(boolean shouldMute) {
        Log.v(this, "mute, shouldMute: %b", shouldMute);

        // Don't mute if there are any emergency calls.
        if (mCallsManager.hasEmergencyCall()) {
            shouldMute = false;
            Log.v(this, "ignoring mute for emergency call");
        }

        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(shouldMute
                ? CallAudioRouteStateMachine.MUTE_ON : CallAudioRouteStateMachine.MUTE_OFF);
    }

    /**
     * Changed the audio route, for example from earpiece to speaker phone.
     *
     * @param route The new audio route to use. See {@link CallAudioState}.
     */
    void setAudioRoute(int route) {
        Log.v(this, "setAudioRoute, route: %s", CallAudioState.audioRouteToString(route));
        switch (route) {
            case CallAudioState.ROUTE_BLUETOOTH:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.SWITCH_BLUETOOTH);
                return;
            case CallAudioState.ROUTE_SPEAKER:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.SWITCH_SPEAKER);
                return;
            case CallAudioState.ROUTE_WIRED_HEADSET:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.SWITCH_HEADSET);
                return;
            case CallAudioState.ROUTE_EARPIECE:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.SWITCH_EARPIECE);
                return;
            case CallAudioState.ROUTE_WIRED_OR_EARPIECE:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.SWITCH_BASELINE_ROUTE);
                return;
            default:
                Log.wtf(this, "Invalid route specified: %d", route);
        }
    }

    void silenceRingers() {
        for (Call call : mRingingCalls) {
            call.silence();
        }

        mRingingCalls.clear();
        mRinger.stopRinging();
        mRinger.stopCallWaiting();
        mCallAudioModeStateMachine.sendMessage(CallAudioModeStateMachine.NO_MORE_RINGING_CALLS,
                makeArgsForModeStateMachine());
    }

    @VisibleForTesting
    public void startRinging() {
        mRinger.startRinging(mForegroundCall);
    }

    @VisibleForTesting
    public void startCallWaiting() {
        mRinger.startCallWaiting(mRingingCalls.iterator().next());
    }

    @VisibleForTesting
    public void stopRinging() {
        mRinger.stopRinging();
    }

    @VisibleForTesting
    public void stopCallWaiting() {
        mRinger.stopCallWaiting();
    }

    @VisibleForTesting
    public void setCallAudioRouteFocusState(int focusState) {
        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                CallAudioRouteStateMachine.SWITCH_FOCUS, focusState);
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Active or dialing calls:");
        pw.increaseIndent();
        dumpCallsInCollection(pw, mActiveOrDialingCalls);
        pw.decreaseIndent();

        pw.println("Ringing calls:");
        pw.increaseIndent();
        dumpCallsInCollection(pw, mRingingCalls);
        pw.decreaseIndent();

        pw.println("Holding calls:");
        pw.increaseIndent();
        dumpCallsInCollection(pw, mHoldingCalls);
        pw.decreaseIndent();

        pw.println("Foreground call:");
        pw.println(mForegroundCall);
    }

    @VisibleForTesting
    public void setIsTonePlaying(boolean isTonePlaying) {
        mIsTonePlaying = isTonePlaying;
        mCallAudioModeStateMachine.sendMessage(
                isTonePlaying ? CallAudioModeStateMachine.TONE_STARTED_PLAYING
                        : CallAudioModeStateMachine.TONE_STOPPED_PLAYING,
                makeArgsForModeStateMachine());
    }

    private void onCallLeavingState(Call call, int state) {
        switch (state) {
            case CallState.ACTIVE:
                onCallLeavingActiveOrDialing();
                break;
            case CallState.RINGING:
                onCallLeavingRinging();
                break;
            case CallState.ON_HOLD:
                onCallLeavingHold();
                break;
            case CallState.DIALING:
                stopRingbackForCall(call);
                onCallLeavingActiveOrDialing();
        }
    }

    private void onCallEnteringState(Call call, int state) {
        switch (state) {
            case CallState.ACTIVE:
                onCallEnteringActiveOrDialing();
                break;
            case CallState.RINGING:
                onCallEnteringRinging();
                break;
            case CallState.ON_HOLD:
                onCallEnteringHold();
                break;
            case CallState.DIALING:
                onCallEnteringActiveOrDialing();
                playRingbackForCall(call);
                break;
        }
    }

    private void onCallLeavingActiveOrDialing() {
        if (mActiveOrDialingCalls.size() == 0) {
            mCallAudioModeStateMachine.sendMessage(
                    CallAudioModeStateMachine.NO_MORE_ACTIVE_OR_DIALING_CALLS,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallLeavingRinging() {
        if (mRingingCalls.size() == 0) {
            mCallAudioModeStateMachine.sendMessage(CallAudioModeStateMachine.NO_MORE_RINGING_CALLS,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallLeavingHold() {
        if (mHoldingCalls.size() == 0) {
            mCallAudioModeStateMachine.sendMessage(CallAudioModeStateMachine.NO_MORE_HOLDING_CALLS,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallEnteringActiveOrDialing() {
        if (mActiveOrDialingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessage(
                    CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallEnteringRinging() {
        if (mRingingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessage(CallAudioModeStateMachine.NEW_RINGING_CALL,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallEnteringHold() {
        if (mHoldingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessage(CallAudioModeStateMachine.NEW_HOLDING_CALL,
                    makeArgsForModeStateMachine());
        }
    }

    private void updateForegroundCall() {
        Call oldForegroundCall = mForegroundCall;
        if (mActiveOrDialingCalls.size() > 0) {
            mForegroundCall = mActiveOrDialingCalls.iterator().next();
        } else if (mRingingCalls.size() > 0) {
            mForegroundCall = mRingingCalls.iterator().next();
        } else if (mHoldingCalls.size() > 0) {
            mForegroundCall = mHoldingCalls.iterator().next();
        } else {
            mForegroundCall = null;
        }

        if (mForegroundCall != oldForegroundCall) {
            mDtmfLocalTonePlayer.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
        }
    }

    @NonNull
    private CallAudioModeStateMachine.MessageArgs makeArgsForModeStateMachine() {
        return new CallAudioModeStateMachine.MessageArgs(
                mActiveOrDialingCalls.size() > 0,
                mRingingCalls.size() > 0,
                mHoldingCalls.size() > 0,
                mIsTonePlaying,
                mForegroundCall != null && mForegroundCall.getIsVoipAudioMode(),
                Log.createSubsession());
    }

    private void playToneForDisconnectedCall(Call call) {
        if (mForegroundCall != null && call != mForegroundCall && mCalls.size() > 1) {
            Log.v(LOG_TAG, "Omitting tone because we are not foreground" +
                    " and there is another call.");
            return;
        }

        if (call.getDisconnectCause() != null) {
            int toneToPlay = InCallTonePlayer.TONE_INVALID;

            Log.v(this, "Disconnect cause: %s.", call.getDisconnectCause());

            switch(call.getDisconnectCause().getTone()) {
                case ToneGenerator.TONE_SUP_BUSY:
                    toneToPlay = InCallTonePlayer.TONE_BUSY;
                    break;
                case ToneGenerator.TONE_SUP_CONGESTION:
                    toneToPlay = InCallTonePlayer.TONE_CONGESTION;
                    break;
                case ToneGenerator.TONE_CDMA_REORDER:
                    toneToPlay = InCallTonePlayer.TONE_REORDER;
                    break;
                case ToneGenerator.TONE_CDMA_ABBR_INTERCEPT:
                    toneToPlay = InCallTonePlayer.TONE_INTERCEPT;
                    break;
                case ToneGenerator.TONE_CDMA_CALLDROP_LITE:
                    toneToPlay = InCallTonePlayer.TONE_CDMA_DROP;
                    break;
                case ToneGenerator.TONE_SUP_ERROR:
                    toneToPlay = InCallTonePlayer.TONE_UNOBTAINABLE_NUMBER;
                    break;
                case ToneGenerator.TONE_PROP_PROMPT:
                    toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                    break;
            }

            Log.d(this, "Found a disconnected call with tone to play %d.", toneToPlay);

            if (toneToPlay != InCallTonePlayer.TONE_INVALID) {
                mPlayerFactory.createPlayer(toneToPlay).startTone();
            }
        }
    }

    private void playRingbackForCall(Call call) {
        if (call == mForegroundCall && call.isRingbackRequested()) {
            mRingbackPlayer.startRingbackForCall(call);
        }
    }

    private void stopRingbackForCall(Call call) {
        mRingbackPlayer.stopRingbackForCall(call);
    }

    private void dumpCallsInCollection(IndentingPrintWriter pw, Collection<Call> calls) {
        for (Call call : calls) {
            if (call != null) pw.println(call.getId());
        }
    }
}