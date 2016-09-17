/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemVibrator;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telecom.CallAudioState;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.AsyncEmergencyContactNotifier;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.TelecomServiceImpl.DefaultDialerManagerAdapter;
import com.android.server.telecom.callfiltering.AsyncBlockCheckFilter;
import com.android.server.telecom.callfiltering.BlockCheckerAdapter;
import com.android.server.telecom.callfiltering.CallFilterResultCallback;
import com.android.server.telecom.callfiltering.CallFilteringResult;
import com.android.server.telecom.callfiltering.CallScreeningServiceFilter;
import com.android.server.telecom.callfiltering.DirectToVoicemailCallFilter;
import com.android.server.telecom.callfiltering.IncomingCallFilter;
import com.android.server.telecom.components.ErrorDialogActivity;

import java.util.ArrayList;
import java.util.Arrays;
import com.android.server.telecom.ui.ViceNotificationImpl;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.codeaurora.ims.QtiCallConstants;
import org.codeaurora.ims.utils.QtiImsExtUtils;
import org.codeaurora.internal.IExtTelephony;
/**
 * Singleton.
 *
 * NOTE: by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.server.telecom package boundary.
 */
@VisibleForTesting
public class CallsManager extends Call.ListenerBase
        implements VideoProviderProxy.Listener, CallFilterResultCallback {

    // TODO: Consider renaming this CallsManagerPlugin.
    @VisibleForTesting
    public interface CallsManagerListener {
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
        void onCallStateChanged(Call call, int oldState, int newState);
        void onConnectionServiceChanged(
                Call call,
                ConnectionServiceWrapper oldService,
                ConnectionServiceWrapper newService);
        void onIncomingCallAnswered(Call call);
        void onIncomingCallRejected(Call call, boolean rejectWithMessage, String textMessage);
        void onCallAudioStateChanged(CallAudioState oldAudioState, CallAudioState newAudioState);
        void onRingbackRequested(Call call, boolean ringback);
        void onIsConferencedChanged(Call call);
        void onIsVoipAudioModeChanged(Call call);
        void onVideoStateChanged(Call call);
        void onCanAddCallChanged(boolean canAddCall);
        void onSessionModifyRequestReceived(Call call, VideoProfile videoProfile);
        void onHoldToneRequested(Call call);
        void onExternalCallChanged(Call call, boolean isExternalCall);
    }

    private static final String TAG = "CallsManager";

    private static final int MAXIMUM_LIVE_CALLS = 1;
    private static final int MAXIMUM_HOLD_CALLS = 1;
    private static final int MAXIMUM_RINGING_CALLS = 1;
    private static final int MAXIMUM_DIALING_CALLS = 1;
    private static final int MAXIMUM_OUTGOING_CALLS = 1;
    private static final int MAXIMUM_TOP_LEVEL_CALLS = 2;
    private static final int MAXIMUM_DSDA_LIVE_CALLS = 2;
    private static final int MAXIMUM_DSDA_HOLD_CALLS = 2;
    private static final int MAXIMUM_DSDA_TOP_LEVEL_CALLS = 4;

    private static final int[] OUTGOING_CALL_STATES =
            {CallState.CONNECTING, CallState.SELECT_PHONE_ACCOUNT, CallState.DIALING};

    private static final int[] LIVE_CALL_STATES =
            {CallState.CONNECTING, CallState.SELECT_PHONE_ACCOUNT, CallState.DIALING,
                CallState.ACTIVE};
    public static final String TELECOM_CALL_ID_PREFIX = "TC@";

    // Maps call technologies in PhoneConstants to those in Analytics.
    private static final Map<Integer, Integer> sAnalyticsTechnologyMap;
    static {
        sAnalyticsTechnologyMap = new HashMap<>(5);
        sAnalyticsTechnologyMap.put(PhoneConstants.PHONE_TYPE_CDMA, Analytics.CDMA_PHONE);
        sAnalyticsTechnologyMap.put(PhoneConstants.PHONE_TYPE_GSM, Analytics.GSM_PHONE);
        sAnalyticsTechnologyMap.put(PhoneConstants.PHONE_TYPE_IMS, Analytics.IMS_PHONE);
        sAnalyticsTechnologyMap.put(PhoneConstants.PHONE_TYPE_SIP, Analytics.SIP_PHONE);
        sAnalyticsTechnologyMap.put(PhoneConstants.PHONE_TYPE_THIRD_PARTY,
                Analytics.THIRD_PARTY_PHONE);
    }

    /**
     * The main call repository. Keeps an instance of all live calls. New incoming and outgoing
     * calls are added to the map and removed when the calls move to the disconnected state.
     *
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Call> mCalls = Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>(8, 0.9f, 1));

    /**
     * The current telecom call ID.  Used when creating new instances of {@link Call}.  Should
     * only be accessed using the {@link #getNextCallId()} method which synchronizes on the
     * {@link #mLock} sync root.
     */
    private int mCallId = 0;

    /**
     * Stores the current foreground user.
     */
    private UserHandle mCurrentUserHandle = UserHandle.of(ActivityManager.getCurrentUser());

    private final ConnectionServiceRepository mConnectionServiceRepository;
    private final DtmfLocalTonePlayer mDtmfLocalTonePlayer;
    private final InCallController mInCallController;
    private final CallAudioManager mCallAudioManager;
    private RespondViaSmsManager mRespondViaSmsManager;
    private final Ringer mRinger;
    private final InCallWakeLockController mInCallWakeLockController;
    // For this set initial table size to 16 because we add 13 listeners in
    // the CallsManager constructor.
    private final Set<CallsManagerListener> mListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<CallsManagerListener, Boolean>(16, 0.9f, 1));
    private final HeadsetMediaButton mHeadsetMediaButton;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private final BluetoothManager mBluetoothManager;
    private final DockManager mDockManager;
    private final TtyManager mTtyManager;
    private final ProximitySensorManager mProximitySensorManager;
    private final PhoneStateBroadcaster mPhoneStateBroadcaster;
    private final CallLogManager mCallLogManager;
    private final Context mContext;
    private final TelecomSystem.SyncRoot mLock;
    private final ContactsAsyncHelper mContactsAsyncHelper;
    private final CallerInfoAsyncQueryFactory mCallerInfoAsyncQueryFactory;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final MissedCallNotifier mMissedCallNotifier;
    private final CallerInfoLookupHelper mCallerInfoLookupHelper;
    private final DefaultDialerManagerAdapter mDefaultDialerManagerAdapter;
    private final Timeouts.Adapter mTimeoutsAdapter;
    private final ViceNotificationImpl mViceNotificationImpl;
    private final Set<Call> mLocallyDisconnectingCalls = new HashSet<>();
    private final Set<Call> mPendingCallsToDisconnect = new HashSet<>();
    /* Handler tied to thread in which CallManager was initialized. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mCanAddCall = true;

    private TelephonyManager.MultiSimVariants mRadioSimVariants = null;

    private static final int LCH_PLAY_DTMF = 100;
    private static final int LCH_STOP_DTMF = 101;
    private static final int PHONE_START_DSDA_INCALL_TONE = 102;
    private static final int SET_LOCAL_CALL_HOLD = 103;
    private static final int LCH_DTMF_PERIODICITY = 3000;
    private static final int LCH_DTMF_PERIOD = 500;
    private static final String sSupervisoryCallHoldToneConfig =
            SystemProperties.get("persist.radio.sch_tone", "none");
    private static final String ACTIVE_SUBSCRIPTION = "active_sub";
    private static InCallTonePlayer.Factory mPlayerFactory;
    private String mLchSub = null;

    private InCallTonePlayer mLocalCallReminderTonePlayer = null;

    private Runnable mStopTone;
    private String mActiveSub = null;
    private DsdaAdapter mDsdaAdapter = null;

    private HashMap<String, Boolean> mLchStatus = new HashMap<String, Boolean>();

    // Two global variables used to handle the Emergency Call when there
    // is no room available for emergency call. Buffer the Emergency Call
    // in mPendingMOEmerCall until the Current Active call is disconnected
    // successfully and place the mPendingMOEmerCall followed by clearing
    // buffer.
    private Call mPendingMOEmerCall = null;
    private Call mDisconnectingCall = null;

    /**
     * Initializes the required Telecom components.
     */
    CallsManager(
            Context context,
            TelecomSystem.SyncRoot lock,
            ContactsAsyncHelper contactsAsyncHelper,
            CallerInfoAsyncQueryFactory callerInfoAsyncQueryFactory,
            MissedCallNotifier missedCallNotifier,
            PhoneAccountRegistrar phoneAccountRegistrar,
            HeadsetMediaButtonFactory headsetMediaButtonFactory,
            ProximitySensorManagerFactory proximitySensorManagerFactory,
            InCallWakeLockControllerFactory inCallWakeLockControllerFactory,
            CallAudioManager.AudioServiceFactory audioServiceFactory,
            BluetoothManager bluetoothManager,
            WiredHeadsetManager wiredHeadsetManager,
            SystemStateProvider systemStateProvider,
            DefaultDialerManagerAdapter defaultDialerAdapter,
            Timeouts.Adapter timeoutsAdapter,
            AsyncRingtonePlayer asyncRingtonePlayer,
            ViceNotifier viceNotifier) {
        mContext = context;
        mLock = lock;
        mContactsAsyncHelper = contactsAsyncHelper;
        mCallerInfoAsyncQueryFactory = callerInfoAsyncQueryFactory;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mMissedCallNotifier = missedCallNotifier;
        StatusBarNotifier statusBarNotifier = new StatusBarNotifier(context, this);
        mWiredHeadsetManager = wiredHeadsetManager;
        mBluetoothManager = bluetoothManager;
        mDefaultDialerManagerAdapter = defaultDialerAdapter;
        mDockManager = new DockManager(context);
        mTimeoutsAdapter = timeoutsAdapter;
        mCallerInfoLookupHelper = new CallerInfoLookupHelper(context, mCallerInfoAsyncQueryFactory,
                mContactsAsyncHelper, mLock);

        mDtmfLocalTonePlayer = new DtmfLocalTonePlayer();
        CallAudioRouteStateMachine callAudioRouteStateMachine = new CallAudioRouteStateMachine(
                context,
                this,
                bluetoothManager,
                wiredHeadsetManager,
                statusBarNotifier,
                audioServiceFactory,
                CallAudioRouteStateMachine.doesDeviceSupportEarpieceRoute()
        );
        callAudioRouteStateMachine.initialize();

        CallAudioRoutePeripheralAdapter callAudioRoutePeripheralAdapter =
                new CallAudioRoutePeripheralAdapter(
                        callAudioRouteStateMachine,
                        bluetoothManager,
                        wiredHeadsetManager,
                        mDockManager);

        InCallTonePlayer.Factory playerFactory = new InCallTonePlayer.Factory(
                callAudioRoutePeripheralAdapter, lock);

        SystemSettingsUtil systemSettingsUtil = new SystemSettingsUtil();
        RingtoneFactory ringtoneFactory = new RingtoneFactory(this, context);
        SystemVibrator systemVibrator = new SystemVibrator(context);
        mInCallController = new InCallController(
                context, mLock, this, systemStateProvider, defaultDialerAdapter);
        mRinger = new Ringer(playerFactory, context, systemSettingsUtil, asyncRingtonePlayer,
                ringtoneFactory, systemVibrator, mInCallController);

        mCallAudioManager = new CallAudioManager(callAudioRouteStateMachine,
                this,new CallAudioModeStateMachine((AudioManager)
                        mContext.getSystemService(Context.AUDIO_SERVICE)),
                playerFactory, mRinger, new RingbackPlayer(playerFactory), mDtmfLocalTonePlayer);

        mHeadsetMediaButton = headsetMediaButtonFactory.create(context, this, mLock);
        mPlayerFactory = playerFactory;
        mTtyManager = new TtyManager(context, mWiredHeadsetManager);
        mProximitySensorManager = proximitySensorManagerFactory.create(context, this);
        mPhoneStateBroadcaster = new PhoneStateBroadcaster(this);
        mCallLogManager = new CallLogManager(context, phoneAccountRegistrar, mMissedCallNotifier);
        mConnectionServiceRepository =
                new ConnectionServiceRepository(mPhoneAccountRegistrar, mContext, mLock, this);
        mInCallWakeLockController = inCallWakeLockControllerFactory.create(context, this);
        mViceNotificationImpl = viceNotifier.create(mContext, this);

        mListeners.add(mInCallWakeLockController);
        mListeners.add(statusBarNotifier);
        mListeners.add(mCallLogManager);
        mListeners.add(mPhoneStateBroadcaster);
        mListeners.add(mInCallController);
        mListeners.add(mCallAudioManager);
        mListeners.add(missedCallNotifier);
        mListeners.add(mHeadsetMediaButton);
        mListeners.add(mProximitySensorManager);
        mListeners.add(mViceNotificationImpl);

        // There is no USER_SWITCHED broadcast for user 0, handle it here explicitly.
        final UserManager userManager = UserManager.get(mContext);
        // Don't load missed call if it is run in split user model.
        if (userManager.isPrimaryUser()) {
            onUserSwitch(Process.myUserHandle());
        }
    }

    ViceNotificationImpl getViceNotificationImpl() {
        return mViceNotificationImpl;
    }

    public void setRespondViaSmsManager(RespondViaSmsManager respondViaSmsManager) {
        if (mRespondViaSmsManager != null) {
            mListeners.remove(mRespondViaSmsManager);
        }
        mRespondViaSmsManager = respondViaSmsManager;
        mListeners.add(respondViaSmsManager);
    }

    public RespondViaSmsManager getRespondViaSmsManager() {
        return mRespondViaSmsManager;
    }

    public CallerInfoLookupHelper getCallerInfoLookupHelper() {
        return mCallerInfoLookupHelper;
    }

    @Override
    public void onSuccessfulOutgoingCall(Call call, int callState) {
        Log.v(this, "onSuccessfulOutgoingCall, %s", call);

        setCallState(call, callState, "successful outgoing call");
        if (!mCalls.contains(call)) {
            // Call was not added previously in startOutgoingCall due to it being a potential MMI
            // code, so add it now.
            addCall(call);
        }

        // The call's ConnectionService has been updated.
        for (CallsManagerListener listener : mListeners) {
            listener.onConnectionServiceChanged(call, null, call.getConnectionService());
        }

        markCallAsDialing(call);
    }

    @Override
    public void onFailedOutgoingCall(Call call, DisconnectCause disconnectCause) {
        Log.v(this, "onFailedOutgoingCall, call: %s", call);

        markCallAsRemoved(call);
    }

    @Override
    public void onSuccessfulIncomingCall(Call incomingCall) {
        Log.d(this, "onSuccessfulIncomingCall");
        List<IncomingCallFilter.CallFilter> filters = new ArrayList<>();
        filters.add(new DirectToVoicemailCallFilter(mCallerInfoLookupHelper));
        filters.add(new AsyncBlockCheckFilter(mContext, new BlockCheckerAdapter()));
        filters.add(new CallScreeningServiceFilter(mContext, this, mPhoneAccountRegistrar,
                mDefaultDialerManagerAdapter,
                new ParcelableCallUtils.Converter(), mLock));
        new IncomingCallFilter(mContext, this, incomingCall, mLock,
                mTimeoutsAdapter, filters).performFiltering();
    }

    @Override
    public void onCallFilteringComplete(Call incomingCall, CallFilteringResult result) {
        // Only set the incoming call as ringing if it isn't already disconnected. It is possible
        // that the connection service disconnected the call before it was even added to Telecom, in
        // which case it makes no sense to set it back to a ringing state.
        if (incomingCall.getState() != CallState.DISCONNECTED &&
                incomingCall.getState() != CallState.DISCONNECTING) {
            setCallState(incomingCall, CallState.RINGING,
                    result.shouldAllowCall ? "successful incoming call" : "blocking call");
        } else {
            Log.i(this, "onCallFilteringCompleted: call already disconnected.");
        }

        if (result.shouldAllowCall) {
            if (hasMaximumRingingCalls(incomingCall.getTargetPhoneAccount().getId())) {
                Log.i(this, "onCallFilteringCompleted: Call rejected! Exceeds maximum number of " +
                        "ringing calls.");
                rejectCallAndLog(incomingCall);
            } else if (hasMaximumDialingCalls()) {
                Log.i(this, "onCallFilteringCompleted: Call rejected! Exceeds maximum number of " +
                        "dialing calls.");
                rejectCallAndLog(incomingCall);
            } else if (!isIncomingVideoCallAllowed(incomingCall, mContext)) {
                Toast.makeText(mContext, mContext.getResources().
                        getString(R.string.incoming_call_failed_low_battery), Toast.LENGTH_LONG).
                        show();
                rejectCallAndLog(incomingCall);
            } else {
                addCall(incomingCall);
                setActiveSubscription(incomingCall.getTargetPhoneAccount().getId());
            }
        } else {
            if (result.shouldReject) {
                Log.i(this, "onCallFilteringCompleted: blocked call, rejecting.");
                incomingCall.reject(false, null);
            }
            if (result.shouldAddToCallLog) {
                Log.i(this, "onCallScreeningCompleted: blocked call, adding to call log.");
                if (result.shouldShowNotification) {
                    Log.w(this, "onCallScreeningCompleted: blocked call, showing notification.");
                }
                mCallLogManager.logCall(incomingCall, Calls.MISSED_TYPE,
                        result.shouldShowNotification);
            } else if (result.shouldShowNotification) {
                Log.i(this, "onCallScreeningCompleted: blocked call, showing notification.");
                mMissedCallNotifier.showMissedCallNotification(incomingCall);
            }
        }
    }

    /**
     * Determines if the incoming video call is allowed or not
     *
     * @param Call The incoming call.
     * @return {@code false} if incoming video call is not allowed.
     */
    private static boolean isIncomingVideoCallAllowed(Call call, Context context) {
        Bundle extras = call.getExtras();
        if (extras == null || (!isIncomingVideoCall(call)) ||
                QtiImsExtUtils.allowVideoCallsInLowBattery(context)) {
            Log.w(TAG, "isIncomingVideoCallAllowed: null Extras or not an incoming video call " +
                    "or allow video calls in low battery");
            return true;
        }

        final boolean isLowBattery = extras.getBoolean(QtiCallConstants.LOW_BATTERY_EXTRA_KEY,
                false);
        Log.d(TAG, "isIncomingVideoCallAllowed: lowbattery = " + isLowBattery);
        return !isLowBattery;
    }

    private static boolean isIncomingVideoCall(Call call) {
        if (VideoProfile.isAudioOnly(call.getVideoState())) {
            return false;
        }

        final int state = call.getState();
        return (state == CallState.RINGING);
    }

    @Override
    public void onFailedIncomingCall(Call call) {
        setCallState(call, CallState.DISCONNECTED, "failed incoming call");
        call.removeListener(this);
    }

    @Override
    public void onSuccessfulUnknownCall(Call call, int callState) {
        setCallState(call, callState, "successful unknown call");
        Log.i(this, "onSuccessfulUnknownCall for call %s", call);
        addCall(call);
    }

    @Override
    public void onFailedUnknownCall(Call call) {
        Log.i(this, "onFailedUnknownCall for call %s", call);
        setCallState(call, CallState.DISCONNECTED, "failed unknown call");
        call.removeListener(this);
    }

    @Override
    public void onRingbackRequested(Call call, boolean ringback) {
        for (CallsManagerListener listener : mListeners) {
            listener.onRingbackRequested(call, ringback);
        }
    }

    @Override
    public void onPostDialWait(Call call, String remaining) {
        mInCallController.onPostDialWait(call, remaining);
    }

    @Override
    public void onPostDialChar(final Call call, char nextChar) {
        if (PhoneNumberUtils.is12Key(nextChar)) {
            // Play tone if it is one of the dialpad digits, canceling out the previously queued
            // up stopTone runnable since playing a new tone automatically stops the previous tone.
            if (mStopTone != null) {
                mHandler.removeCallbacks(mStopTone.getRunnableToCancel());
                mStopTone.cancel();
            }

            mDtmfLocalTonePlayer.playTone(call, nextChar);

            // TODO: Create a LockedRunnable class that does the synchronization automatically.
            mStopTone = new Runnable("CM.oPDC") {
                @Override
                public void loggedRun() {
                    synchronized (mLock) {
                        // Set a timeout to stop the tone in case there isn't another tone to
                        // follow.
                        mDtmfLocalTonePlayer.stopTone(call);
                    }
                }
            };
            mHandler.postDelayed(mStopTone.prepare(),
                    Timeouts.getDelayBetweenDtmfTonesMillis(mContext.getContentResolver()));
        } else if (nextChar == 0 || nextChar == TelecomManager.DTMF_CHARACTER_WAIT ||
                nextChar == TelecomManager.DTMF_CHARACTER_PAUSE) {
            // Stop the tone if a tone is playing, removing any other stopTone callbacks since
            // the previous tone is being stopped anyway.
            if (mStopTone != null) {
                mHandler.removeCallbacks(mStopTone.getRunnableToCancel());
                mStopTone.cancel();
            }
            mDtmfLocalTonePlayer.stopTone(call);
        } else {
            Log.w(this, "onPostDialChar: invalid value %d", nextChar);
        }
    }

    @Override
    public void onParentChanged(Call call) {
        // parent-child relationship affects which call should be foreground, so do an update.
        updateCallsManagerState();
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onChildrenChanged(Call call) {
        // parent-child relationship affects which call should be foreground, so do an update.
        updateCallsManagerState();
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onIsVoipAudioModeChanged(call);
        }
    }

    @Override
    public void onVideoStateChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onVideoStateChanged(call);
        }
    }

    @Override
    public boolean onCanceledViaNewOutgoingCallBroadcast(final Call call) {
        mPendingCallsToDisconnect.add(call);
        mHandler.postDelayed(new Runnable("CM.oCVNOCB") {
            @Override
            public void loggedRun() {
                synchronized (mLock) {
                    if (mPendingCallsToDisconnect.remove(call)) {
                        Log.i(this, "Delayed disconnection of call: %s", call);
                        call.disconnect();
                    }
                }
            }
        }.prepare(), Timeouts.getNewOutgoingCallCancelMillis(mContext.getContentResolver()));

        return true;
    }

    /**
     * Handles changes to the {@link Connection.VideoProvider} for a call.  Adds the
     * {@link CallsManager} as a listener for the {@link VideoProviderProxy} which is created
     * in {@link Call#setVideoProvider(IVideoProvider)}.  This allows the {@link CallsManager} to
     * respond to callbacks from the {@link VideoProviderProxy}.
     *
     * @param call The call.
     */
    @Override
    public void onVideoCallProviderChanged(Call call) {
        VideoProviderProxy videoProviderProxy = call.getVideoProviderProxy();

        if (videoProviderProxy == null) {
            return;
        }

        videoProviderProxy.addListener(this);
    }

    /**
     * Handles session modification requests received via the {@link TelecomVideoCallCallback} for
     * a call.  Notifies listeners of the {@link CallsManager.CallsManagerListener} of the session
     * modification request.
     *
     * @param call The call.
     * @param videoProfile The {@link VideoProfile}.
     */
    @Override
    public void onSessionModifyRequestReceived(Call call, VideoProfile videoProfile) {
        int videoState = videoProfile != null ? videoProfile.getVideoState() :
                VideoProfile.STATE_AUDIO_ONLY;
        Log.v(TAG, "onSessionModifyRequestReceived : videoProfile = " + VideoProfile
                .videoStateToString(videoState));

        for (CallsManagerListener listener : mListeners) {
            listener.onSessionModifyRequestReceived(call, videoProfile);
        }
    }

    public Collection<Call> getCalls() {
        return Collections.unmodifiableCollection(mCalls);
    }

    /**
     * Play or stop a call hold tone for a call.  Triggered via
     * {@link Connection#sendConnectionEvent(String)} when the
     * {@link Connection#EVENT_ON_HOLD_TONE_START} event or
     * {@link Connection#EVENT_ON_HOLD_TONE_STOP} event is passed through to the
     *
     * @param call The call which requested the hold tone.
     */
    @Override
    public void onHoldToneRequested(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onHoldToneRequested(call);
        }
    }

    @VisibleForTesting
    public Call getForegroundCall() {
        if (mCallAudioManager == null) {
            // Happens when getForegroundCall is called before full initialization.
            return null;
        }
        return mCallAudioManager.getForegroundCall();
    }

    public UserHandle getCurrentUserHandle() {
        return mCurrentUserHandle;
    }

    public CallAudioManager getCallAudioManager() {
        return mCallAudioManager;
    }

    InCallController getInCallController() {
        return mInCallController;
    }

    @VisibleForTesting
    public boolean hasEmergencyCall() {
        for (Call call : mCalls) {
            if (call.isEmergencyCall()) {
                return true;
            }
        }
        return false;
    }

    boolean hasOnlyDisconnectedCalls() {
        for (Call call : mCalls) {
            if (!call.isDisconnected()) {
                return false;
            }
        }
        return true;
    }

    boolean hasVideoCall() {
        for (Call call : mCalls) {
            if (VideoProfile.isVideo(call.getVideoState())) {
                return true;
            }
        }
        return false;
    }

    CallAudioState getAudioState() {
        return mCallAudioManager.getCallAudioState();
    }

    boolean isTtySupported() {
        return mTtyManager.isTtySupported();
    }

    int getCurrentTtyMode() {
        return mTtyManager.getCurrentTtyMode();
    }

    @VisibleForTesting
    public void addListener(CallsManagerListener listener) {
        mListeners.add(listener);
    }

    void removeListener(CallsManagerListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Starts the process to attach the call to a connection service.
     *
     * @param phoneAccountHandle The phone account which contains the component name of the
     *        connection service to use for this call.
     * @param extras The optional extras Bundle passed with the intent used for the incoming call.
     */
    void processIncomingCallIntent(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        Log.d(this, "processIncomingCallIntent");
        Uri handle = extras.getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS);
        if (handle == null) {
            // Required for backwards compatibility
            handle = extras.getParcelable(TelephonyManager.EXTRA_INCOMING_NUMBER);
        }
        Call call = new Call(
                getNextCallId(),
                mContext,
                this,
                mLock,
                mConnectionServiceRepository,
                mContactsAsyncHelper,
                mCallerInfoAsyncQueryFactory,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                Call.CALL_DIRECTION_INCOMING /* callDirection */,
                false /* forceAttachToExistingConnection */,
                false /* isConference */
        );

        call.initAnalytics();
        if (getForegroundCall() != null) {
            getForegroundCall().getAnalytics().setCallIsInterrupted(true);
            call.getAnalytics().setCallIsAdditional(true);
        }

        setIntentExtrasAndStartTime(call, extras);
        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        call.startCreateConnection(mPhoneAccountRegistrar);
    }

    void addNewUnknownCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        Uri handle = extras.getParcelable(TelecomManager.EXTRA_UNKNOWN_CALL_HANDLE);
        Log.i(this, "addNewUnknownCall with handle: %s", Log.pii(handle));
        Call call = new Call(
                getNextCallId(),
                mContext,
                this,
                mLock,
                mConnectionServiceRepository,
                mContactsAsyncHelper,
                mCallerInfoAsyncQueryFactory,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                Call.CALL_DIRECTION_UNKNOWN /* callDirection */,
                // Use onCreateIncomingConnection in TelephonyConnectionService, so that we attach
                // to the existing connection instead of trying to create a new one.
                true /* forceAttachToExistingConnection */,
                false /* isConference */
        );
        call.initAnalytics();

        setIntentExtrasAndStartTime(call, extras);
        call.addListener(this);
        call.startCreateConnection(mPhoneAccountRegistrar);
    }

    private boolean areHandlesEqual(Uri handle1, Uri handle2) {
        if (handle1 == null || handle2 == null) {
            return handle1 == handle2;
        }

        if (!TextUtils.equals(handle1.getScheme(), handle2.getScheme())) {
            return false;
        }

        final String number1 = PhoneNumberUtils.normalizeNumber(handle1.getSchemeSpecificPart());
        final String number2 = PhoneNumberUtils.normalizeNumber(handle2.getSchemeSpecificPart());
        return TextUtils.equals(number1, number2);
    }

    private Call reuseOutgoingCall(Uri handle) {
        // Check to see if we can reuse any of the calls that are waiting to disconnect.
        // See {@link Call#abort} and {@link #onCanceledViaNewOutgoingCall} for more information.
        Call reusedCall = null;
        for (Call pendingCall : mPendingCallsToDisconnect) {
            if (reusedCall == null && areHandlesEqual(pendingCall.getHandle(), handle)) {
                mPendingCallsToDisconnect.remove(pendingCall);
                Log.i(this, "Reusing disconnected call %s", pendingCall);
                reusedCall = pendingCall;
            } else {
                Log.i(this, "Not reusing disconnected call %s", pendingCall);
                pendingCall.disconnect();
            }
        }

        return reusedCall;
    }

    /**
     * Kicks off the first steps to creating an outgoing call so that InCallUI can launch.
     *
     * @param handle Handle to connect the call with.
     * @param phoneAccountHandle The phone account which contains the component name of the
     *        connection service to use for this call.
     * @param extras The optional extras Bundle passed with the intent used for the incoming call.
     * @param initiatingUser {@link UserHandle} of user that place the outgoing call.
     */
    Call startOutgoingCall(Uri handle, PhoneAccountHandle phoneAccountHandle, Bundle extras,
            UserHandle initiatingUser) {
        boolean isReusedCall = true;
        Call call = reuseOutgoingCall(handle);

        // Create a call with original handle. The handle may be changed when the call is attached
        // to a connection service, but in most cases will remain the same.
        if (call == null) {
            call = new Call(getNextCallId(), mContext,
                    this,
                    mLock,
                    mConnectionServiceRepository,
                    mContactsAsyncHelper,
                    mCallerInfoAsyncQueryFactory,
                    handle,
                    null /* gatewayInfo */,
                    null /* connectionManagerPhoneAccount */,
                    null /* phoneAccountHandle */,
                    Call.CALL_DIRECTION_OUTGOING /* callDirection */,
                    false /* forceAttachToExistingConnection */,
                    false /* isConference */
            );
            call.setInitiatingUser(initiatingUser);

            call.initAnalytics();

            isReusedCall = false;
        }

        // Set the video state on the call early so that when it is added to the InCall UI the UI
        // knows to configure itself as a video call immediately.
        if (extras != null) {
            int videoState = extras.getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    VideoProfile.STATE_AUDIO_ONLY);

            // If this is an emergency video call, we need to check if the phone account supports
            // emergency video calling.
            if (call.isEmergencyCall() && VideoProfile.isVideo(videoState)) {
                PhoneAccount account =
                        mPhoneAccountRegistrar.getPhoneAccount(phoneAccountHandle, initiatingUser);

                if (account != null &&
                        !account.hasCapabilities(PhoneAccount.CAPABILITY_EMERGENCY_VIDEO_CALLING)) {
                    // Phone account doesn't support emergency video calling, so fallback to
                    // audio-only now to prevent the InCall UI from setting up video surfaces
                    // needlessly.
                    Log.i(this, "startOutgoingCall - emergency video calls not supported; " +
                            "falling back to audio-only");
                    videoState = VideoProfile.STATE_AUDIO_ONLY;
                }
            }

            call.setVideoState(videoState);
        }

        boolean isAddParticipant = ((extras != null) && (extras.getBoolean(
                TelephonyProperties.ADD_PARTICIPANT_KEY, false)));
        boolean isSkipSchemaOrConfUri = ((extras != null) && (extras.getBoolean(
                TelephonyProperties.EXTRA_SKIP_SCHEMA_PARSING, false) ||
                extras.getBoolean(TelephonyProperties.EXTRA_DIAL_CONFERENCE_URI, false)));

        if (isAddParticipant) {
            String number = handle.getSchemeSpecificPart();
            if (!isSkipSchemaOrConfUri) {
                number = PhoneNumberUtils.stripSeparators(number);
            }
            addParticipant(number);
            mInCallController.bringToForeground(false);
            return null;
        }

        // Force tel scheme for ims conf uri/skip schema calls to avoid selection of sip accounts
        String scheme = (isSkipSchemaOrConfUri? PhoneAccount.SCHEME_TEL: handle.getScheme());

        Log.d(this, "startOutgoingCall :: isAddParticipant=" + isAddParticipant
                + " isSkipSchemaOrConfUri=" + isSkipSchemaOrConfUri + " scheme=" + scheme);

        List<PhoneAccountHandle> accounts =
                constructPossiblePhoneAccounts(handle, initiatingUser, scheme);
        Log.v(this, "startOutgoingCall found accounts = " + accounts);

        // Only dial with the requested phoneAccount if it is still valid. Otherwise treat this call
        // as if a phoneAccount was not specified (does the default behavior instead).
        // Note: We will not attempt to dial with a requested phoneAccount if it is disabled.
        if (phoneAccountHandle != null) {
            if (!accounts.contains(phoneAccountHandle)) {
                phoneAccountHandle = null;
            }
        }

        if (phoneAccountHandle == null && accounts.size() > 0 && !call.isEmergencyCall()) {
            // No preset account, check if default exists that supports the URI scheme for the
            // handle and verify it can be used.
            if(accounts.size() > 1) {
                PhoneAccountHandle defaultPhoneAccountHandle =
                        mPhoneAccountRegistrar.getOutgoingPhoneAccountForScheme(scheme,
                                initiatingUser);
                if (defaultPhoneAccountHandle != null &&
                        accounts.contains(defaultPhoneAccountHandle)) {
                    phoneAccountHandle = defaultPhoneAccountHandle;
                }
            } else {
                // Use the only PhoneAccount that is available
                phoneAccountHandle = accounts.get(0);
            }
        }

        call.setTargetPhoneAccount(phoneAccountHandle);

        boolean isPotentialInCallMMICode = isPotentialInCallMMICode(handle);

        // Do not support any more live calls.  Our options are to move a call to hold, disconnect
        // a call, or cancel this call altogether. If a call is being reused, then it has already
        // passed the makeRoomForOutgoingCall check once and will fail the second time due to the
        // call transitioning into the CONNECTING state.
        if (!isPotentialInCallMMICode && (!isReusedCall &&
                !makeRoomForOutgoingCall(call, call.isEmergencyCall()))) {
            // just cancel at this point.
            Log.i(this, "No remaining room for outgoing call: %s", call);
            if (mCalls.contains(call)) {
                // This call can already exist if it is a reused call,
                // See {@link #reuseOutgoingCall}.
                call.disconnect();
            }
            return null;
        }

        boolean needsAccountSelection = phoneAccountHandle == null && accounts.size() > 1 &&
                !call.isEmergencyCall();

        if (needsAccountSelection) {
            // This is the state where the user is expected to select an account
            call.setState(CallState.SELECT_PHONE_ACCOUNT, "needs account selection");
            // Create our own instance to modify (since extras may be Bundle.EMPTY)
            extras = new Bundle(extras);
            extras.putParcelableList(android.telecom.Call.AVAILABLE_PHONE_ACCOUNTS, accounts);
        } else {
            call.setState(
                    CallState.CONNECTING,
                    phoneAccountHandle == null ? "no-handle" : phoneAccountHandle.toString());
        }

        setIntentExtrasAndStartTime(call, extras);

        // Do not add the call if it is a potential MMI code.
        if ((isPotentialMMICode(handle) || isPotentialInCallMMICode) && !needsAccountSelection) {
            call.addListener(this);
        // If call is Emergency type and marked it as Pending, call would not be added
        // in mCalls here. It will be handled when the current active call (mDisconnectingCall)
        // is disconnected successfully.
        } else if (!mCalls.contains(call) && mPendingMOEmerCall == null) {
            // We check if mCalls already contains the call because we could potentially be reusing
            // a call which was previously added (See {@link #reuseOutgoingCall}).
            addCall(call);
        }

        return call;
    }

    /**
     * Attempts to issue/connect the specified call.
     *
     * @param handle Handle to connect the call with.
     * @param gatewayInfo Optional gateway information that can be used to route the call to the
     *        actual dialed handle via a gateway provider. May be null.
     * @param speakerphoneOn Whether or not to turn the speakerphone on once the call connects.
     * @param videoState The desired video state for the outgoing call.
     */
    @VisibleForTesting
    public void placeOutgoingCall(Call call, Uri handle, GatewayInfo gatewayInfo,
            boolean speakerphoneOn, int videoState) {
        if (call == null) {
            // don't do anything if the call no longer exists
            Log.i(this, "Canceling unknown call.");
            return;
        }

        final Uri uriHandle = (gatewayInfo == null) ? handle : gatewayInfo.getGatewayAddress();

        if (gatewayInfo == null) {
            Log.i(this, "Creating a new outgoing call with handle: %s", Log.piiHandle(uriHandle));
        } else {
            Log.i(this, "Creating a new outgoing call with gateway handle: %s, original handle: %s",
                    Log.pii(uriHandle), Log.pii(handle));
        }

        call.setHandle(uriHandle);
        call.setGatewayInfo(gatewayInfo);

        final boolean useSpeakerWhenDocked = mContext.getResources().getBoolean(
                R.bool.use_speaker_when_docked);
        final boolean isDocked = mDockManager.isDocked();
        final boolean useSpeakerForVideoCall = isSpeakerphoneAutoEnabled(videoState);

        // Auto-enable speakerphone if the originating intent specified to do so, if the call
        // is a video call, of if using speaker when docked
        call.setStartWithSpeakerphoneOn(speakerphoneOn || useSpeakerForVideoCall
                || (useSpeakerWhenDocked && isDocked));
        call.setVideoState(videoState);

        if (speakerphoneOn) {
            Log.i(this, "%s Starting with speakerphone as requested", call);
        } else if (useSpeakerWhenDocked && useSpeakerWhenDocked) {
            Log.i(this, "%s Starting with speakerphone because car is docked.", call);
        } else if (useSpeakerForVideoCall) {
            Log.i(this, "%s Starting with speakerphone because its a video call.", call);
        }

        if (call.isEmergencyCall()) {
            // Emergency -- CreateConnectionProcessor will choose accounts automatically
            call.setTargetPhoneAccount(null);
            new AsyncEmergencyContactNotifier(mContext).execute();
        }

        final boolean requireCallCapableAccountByHandle = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_requireCallCapableAccountForHandle);

        if (call.getTargetPhoneAccount() != null || call.isEmergencyCall()) {
            if (!call.isEmergencyCall()) {
                updateLchStatus(call.getTargetPhoneAccount().getId());
            }
            //Block to initiate Emregency call now as the current active call
            //is not yet disconnected.
            if (mPendingMOEmerCall == null) {
            // If the account has been set, proceed to place the outgoing call.
            // Otherwise the connection will be initiated when the account is set by the user.
                call.startCreateConnection(mPhoneAccountRegistrar);
            }
        } else if (mPhoneAccountRegistrar.getCallCapablePhoneAccounts(
                requireCallCapableAccountByHandle ? call.getHandle().getScheme() : null, false,
                call.getInitiatingUser()).isEmpty()) {
            // If there are no call capable accounts, disconnect the call.
            markCallAsDisconnected(call, new DisconnectCause(DisconnectCause.CANCELED,
                    "No registered PhoneAccounts"));
            markCallAsRemoved(call);
        }
    }

    /**
     * Attempts to add participant in a call.
     *
     * @param number number to connect the call with.
     */
    private void addParticipant(String number) {
        Log.i(this, "addParticipant number ="+number);
        if (getForegroundCall() == null) {
            // don't do anything if the call no longer exists
            Log.i(this, "Canceling unknown call.");
            return;
        } else {
            getForegroundCall().addParticipantWithConference(number);
        }
    }


    /**
     * Attempts to start a conference call for the specified call.
     *
     * @param call The call to conference.
     * @param otherCall The other call to conference with.
     */
    @VisibleForTesting
    public void conference(Call call, Call otherCall) {
        call.conferenceWith(otherCall);
    }

    /**
     * Instructs Telecom to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecom notifies it of an incoming call followed by
     * the user opting to answer said call.
     *
     * @param call The call to answer.
     * @param videoState The video state in which to answer the call.
     */
    @VisibleForTesting
    public void answerCall(Call call, int videoState) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to answer a non-existent call %s", call);
        } else {
            Call activeCall = getFirstCallWithState(call.getTargetPhoneAccount()
                    .getId(), CallState.ACTIVE, CallState.DIALING);
            // If the foreground call is not the ringing call and it is currently isActive() or
            // STATE_DIALING, put it on hold before answering the call.
            if (activeCall != null && activeCall != call &&
                    (activeCall.isActive() ||
                     activeCall.getState() == CallState.DIALING)) {
                if (0 == (activeCall.getConnectionCapabilities()
                        & Connection.CAPABILITY_HOLD)) {
                    // This call does not support hold.  If it is from a different connection
                    // service, then disconnect it, otherwise allow the connection service to
                    // figure out the right states.
                    if (activeCall.getConnectionService() != call.getConnectionService()) {
                        activeCall.disconnect();
                    }
                } else {
                    Call heldCall = getHeldCall();
                    if (heldCall != null) {
                        Log.v(this, "Disconnecting held call %s before holding active call.",
                                heldCall);
                        heldCall.disconnect();
                    }

                    Log.v(this, "Holding active/dialing call %s before answering incoming call %s.",
                            activeCall, call);
                    activeCall.hold();
                }
                // TODO: Wait until we get confirmation of the active call being
                // on-hold before answering the new call.
                // TODO: Import logic from CallManager.acceptCall()
            }

            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallAnswered(call);
            }
            updateLchStatus(call.getTargetPhoneAccount().getId());
            // We do not update the UI until we get confirmation of the answer() through
            // {@link #markCallAsActive}.
            call.answer(videoState);
            if (isSpeakerphoneAutoEnabled(videoState)) {
                call.setStartWithSpeakerphoneOn(true);
            }
        }
    }

    /**
     * Determines if the speakerphone should be automatically enabled for the call.  Speakerphone
     * should be enabled if the call is a video call and bluetooth or the wired headset are not in
     * use.
     *
     * @param videoState The video state of the call.
     * @return {@code true} if the speakerphone should be enabled.
     */
    private boolean isSpeakerphoneAutoEnabled(int videoState) {
        return VideoProfile.isVideo(videoState) &&
            !mWiredHeadsetManager.isPluggedIn() &&
            !mBluetoothManager.isBluetoothAvailable() &&
            isSpeakerEnabledForVideoCalls();
    }

    /**
     * Determines if the speakerphone should be automatically enabled for video calls.
     *
     * @return {@code true} if the speakerphone should automatically be enabled.
     */
    private static boolean isSpeakerEnabledForVideoCalls() {
        return (SystemProperties.getInt(TelephonyProperties.PROPERTY_VIDEOCALL_AUDIO_OUTPUT,
                PhoneConstants.AUDIO_OUTPUT_DEFAULT) ==
                PhoneConstants.AUDIO_OUTPUT_ENABLE_SPEAKER);
    }

    /**
     * Instructs Telecom to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecom notifies it of an incoming call followed by
     * the user opting to reject said call.
     */
    @VisibleForTesting
    public void rejectCall(Call call, boolean rejectWithMessage, String textMessage) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to reject a non-existent call %s", call);
        } else {
            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallRejected(call, rejectWithMessage, textMessage);
            }
            setActiveSubscription(getConversationSub());
            call.reject(rejectWithMessage, textMessage);
        }
    }

    /**
     * Instructs Telecom to play the specified DTMF tone within the specified call.
     *
     * @param digit The DTMF digit to play.
     */
    @VisibleForTesting
    public void playDtmfTone(Call call, char digit) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to play DTMF in a non-existent call %s", call);
        } else {
            call.playDtmfTone(digit);
            mDtmfLocalTonePlayer.playTone(call, digit);
        }
    }

    /**
     * Instructs Telecom to stop the currently playing DTMF tone, if any.
     */
    @VisibleForTesting
    public void stopDtmfTone(Call call) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to stop DTMF in a non-existent call %s", call);
        } else {
            call.stopDtmfTone();
            mDtmfLocalTonePlayer.stopTone(call);
        }
    }

    /**
     * Instructs Telecom to continue (or not) the current post-dial DTMF string, if any.
     */
    void postDialContinue(Call call, boolean proceed) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to continue post-dial string in a non-existent call %s", call);
        } else {
            call.postDialContinue(proceed);
        }
    }

    /**
     * Instructs Telecom to disconnect the specified call. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the end-call button.
     */
    @VisibleForTesting
    public void disconnectCall(Call call) {
        Log.v(this, "disconnectCall %s", call);

        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to disconnect", call);
        } else {
            mLocallyDisconnectingCalls.add(call);
            call.disconnect();
        }
    }

    /**
     * Instructs Telecom to disconnect all calls.
     */
    void disconnectAllCalls() {
        Log.v(this, "disconnectAllCalls");

        for (Call call : mCalls) {
            disconnectCall(call);
        }
    }


    /**
     * Instructs Telecom to put the specified call on hold. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the hold button during an active call.
     */
    @VisibleForTesting
    public void holdCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to be put on hold", call);
        } else {
            Log.d(this, "Putting call on hold: (%s)", call);
            call.hold();
        }
    }

    /**
     * Instructs Telecom to release the specified call from hold. Intended to be invoked by
     * the in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered
     * by the user hitting the hold button during a held call.
     */
    @VisibleForTesting
    public void unholdCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to be removed from hold", call);
        } else {
            Log.d(this, "unholding call: (%s)", call);
            for (Call c : mCalls) {
                PhoneAccountHandle ph = call.getTargetPhoneAccount();
                PhoneAccountHandle ph1 = c.getTargetPhoneAccount();
                // Only attempt to hold parent calls and not the individual children.
                // if 'c' is not for same subscription as call, then don't disturb 'c'
                if (c != null && c.isAlive() && c != call && c.getParentCall() == null
                        && (ph != null && ph1 != null &&
                        isSamePhAccIdOrSipId(ph.getId(), ph1.getId()))) {
                    c.hold();
                }
            }
            call.unhold();
        }
    }

    @Override
    public void onExtrasChanged(Call c, int source, Bundle extras) {
        if (source != Call.SOURCE_CONNECTION_SERVICE) {
            return;
        }

        handleCallTechnologyChange(c);
        handleChildAddressChange(c);

        if (extras != null) {
            boolean isNeedReset = extras.getBoolean("isNeedReset", false);
            handleCdmaConnectionTimeReset(c, isNeedReset);
        }
    }

    void handleCdmaConnectionTimeReset(Call call, boolean isNeedReset) {
        if (isNeedReset && call != null) {
            call.setConnectTimeMillis(System.currentTimeMillis());
            if (mCalls.contains(call)) {
                for (CallsManagerListener listener : mListeners) {
                    listener.onCallStateChanged(call, CallState.ACTIVE, CallState.ACTIVE);
                }
            }
            call.removeExtras(Call.SOURCE_INCALL_SERVICE,
                    new ArrayList<String>(Arrays.asList("isNeedReset")));
        }
    }

    // Construct the list of possible PhoneAccounts that the outgoing call can use based on the
    // active calls in CallsManager. If any of the active calls are on a SIM based PhoneAccount,
    // then include only that SIM based PhoneAccount and any non-SIM PhoneAccounts, such as SIP.
    private List<PhoneAccountHandle> constructPossiblePhoneAccounts(
            Uri handle, UserHandle user, String scheme) {
        if (handle == null) {
            return Collections.emptyList();
        }
        if (scheme == null) {
            scheme = handle.getScheme();
        }
        List<PhoneAccountHandle> allAccounts =
                mPhoneAccountRegistrar.getCallCapablePhoneAccounts(scheme, false, user);
        // First check the Radio SIM Technology
        if(mRadioSimVariants == null) {
            TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
            // Cache Sim Variants
            mRadioSimVariants = tm.getMultiSimConfiguration();
        }
        // Only one SIM PhoneAccount can be active at one time for DSDS. Only that SIM PhoneAccount
        // Should be available if a call is already active on the SIM account.
        if(mRadioSimVariants != TelephonyManager.MultiSimVariants.DSDA) {
            List<PhoneAccountHandle> simAccounts =
                    mPhoneAccountRegistrar.getSimPhoneAccountsOfCurrentUser();
            PhoneAccountHandle ongoingCallAccount = null;
            for (Call c : mCalls) {
                if (!c.isDisconnected() && !c.isNew() && simAccounts.contains(
                        c.getTargetPhoneAccount())) {
                    ongoingCallAccount = c.getTargetPhoneAccount();
                    break;
                }
            }
            if (ongoingCallAccount != null) {
                // Remove all SIM accounts that are not the active SIM from the list.
                simAccounts.remove(ongoingCallAccount);
                allAccounts.removeAll(simAccounts);
            }
        }
        return allAccounts;
    }

    /**
     * Informs listeners (notably {@link CallAudioManager} of a change to the call's external
     * property.
     * .
     * @param call The call whose external property changed.
     * @param isExternalCall {@code True} if the call is now external, {@code false} otherwise.
     */
    @Override
    public void onExternalCallChanged(Call call, boolean isExternalCall) {
        Log.v(this, "onConnectionPropertiesChanged: %b", isExternalCall);
        for (CallsManagerListener listener : mListeners) {
            listener.onExternalCallChanged(call, isExternalCall);
        }
    }

    private void handleCallTechnologyChange(Call call) {
        if (call.getExtras() != null
                && call.getExtras().containsKey(TelecomManager.EXTRA_CALL_TECHNOLOGY_TYPE)) {

            Integer analyticsCallTechnology = sAnalyticsTechnologyMap.get(
                    call.getExtras().getInt(TelecomManager.EXTRA_CALL_TECHNOLOGY_TYPE));
            if (analyticsCallTechnology == null) {
                analyticsCallTechnology = Analytics.THIRD_PARTY_PHONE;
            }
            call.getAnalytics().addCallTechnology(analyticsCallTechnology);
        }
    }

    public void handleChildAddressChange(Call call) {
        if (call.getExtras() != null
                && call.getExtras().containsKey(Connection.EXTRA_CHILD_ADDRESS)) {

            String viaNumber = call.getExtras().getString(Connection.EXTRA_CHILD_ADDRESS);
            call.setViaNumber(viaNumber);
        }
    }
    /**
     *  Returns true if the ids are same or one of the ids is sip id.
     */
    private boolean isSamePhAccIdOrSipId(String id1, String id2) {
        boolean ret = ((id1 != null && id2 != null) &&
                (id1.equals(id2) || id1.contains("sip") || id2.contains("sip")));
        Log.d(this, "isSamePhAccIdOrSipId: id1 = " + id1 + " id2 = " + id2 + " ret = " + ret);
        return ret;
    }

    /** Called by the in-call UI to change the mute state. */
    void mute(boolean shouldMute) {
        mCallAudioManager.mute(shouldMute);
    }

    /**
      * Called by the in-call UI to change the audio route, for example to change from earpiece to
      * speaker phone.
      */
    void setAudioRoute(int route) {
        Call call = getDialingCall();
        if (call != null && call.getStartWithSpeakerphoneOn()) {
            /* There is a change in audio routing preferance for the call.
             * So, honour the new audio routing preferance.
             */
            call.setStartWithSpeakerphoneOn(false);
        }
        mCallAudioManager.setAudioRoute(route);
    }

    /** Called by the in-call UI to turn the proximity sensor on. */
    void turnOnProximitySensor() {
        mProximitySensorManager.turnOn();
    }

    /**
     * Called by the in-call UI to turn the proximity sensor off.
     * @param screenOnImmediately If true, the screen will be turned on immediately. Otherwise,
     *        the screen will be kept off until the proximity sensor goes negative.
     */
    void turnOffProximitySensor(boolean screenOnImmediately) {
        mProximitySensorManager.turnOff(screenOnImmediately);
    }

    void phoneAccountSelected(Call call, PhoneAccountHandle account, boolean setDefault) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Attempted to add account to unknown call %s", call);
        } else {
            Log.i(this, "phoneAccountSelected , id = %s", account.getId());
            updateLchStatus(account.getId());
            call.setTargetPhoneAccount(account);

            if (!call.isNewOutgoingCallIntentBroadcastDone()) {
                return;
            }

            // Note: emergency calls never go through account selection dialog so they never
            // arrive here.
            if (makeRoomForOutgoingCall(call, false /* isEmergencyCall */)) {
                call.startCreateConnection(mPhoneAccountRegistrar);
            } else {
                call.disconnect();
            }

            if (setDefault) {
                mPhoneAccountRegistrar
                        .setUserSelectedOutgoingPhoneAccount(account, call.getInitiatingUser());
            }
        }
    }

    /** Called when the audio state changes. */
    @VisibleForTesting
    public void onCallAudioStateChanged(CallAudioState oldAudioState, CallAudioState
            newAudioState) {
        Log.v(this, "onAudioStateChanged, audioState: %s -> %s", oldAudioState, newAudioState);
        for (CallsManagerListener listener : mListeners) {
            listener.onCallAudioStateChanged(oldAudioState, newAudioState);
        }
    }

    void markCallAsRinging(Call call) {
        setCallState(call, CallState.RINGING, "ringing set explicitly");
    }

    void markCallAsDialing(Call call) {
        setCallState(call, CallState.DIALING, "dialing set explicitly");
        maybeMoveToEarpiece(call);
        maybeMoveToSpeakerPhone(call);
        setActiveSubscription(call.getTargetPhoneAccount().getId());
    }

    void markCallAsActive(Call call) {
        setCallState(call, CallState.ACTIVE, "active set explicitly");
        maybeMoveToSpeakerPhone(call);
    }

    void markCallAsOnHold(Call call) {
        setCallState(call, CallState.ON_HOLD, "on-hold set explicitly");
    }

    /**
     * Marks the specified call as STATE_DISCONNECTED and notifies the in-call app. If this was the
     * last live call, then also disconnect from the in-call controller.
     *
     * @param disconnectCause The disconnect cause, see {@link android.telecom.DisconnectCause}.
     */
    void markCallAsDisconnected(Call call, DisconnectCause disconnectCause) {
        call.setDisconnectCause(disconnectCause);
        int prevState = call.getState();
        setCallState(call, CallState.DISCONNECTED, "disconnected set explicitly");
        String lchSub = getLchSub();
        String subId = (call.getTargetPhoneAccount() == null) ? null :
                call.getTargetPhoneAccount().getId();
        String subInConversation = getConversationSub();
        if (subId != null && subId.equals(mActiveSub) &&
                getFirstCallWithState(subId, CallState.RINGING) == null  &&
                (subInConversation != null && !subInConversation.equals(mActiveSub))) {
            Log.d(this,"Set active sub to conversation sub");
            switchToOtherActiveSub(subInConversation);
        } else if ((subInConversation == null) && (lchSub != null) &&
                ((prevState == CallState.CONNECTING) || (prevState ==
                CallState.SELECT_PHONE_ACCOUNT)) &&
                (call.getState() == CallState.DISCONNECTED)) {
            Log.d(this,"remove sub with call from LCH");
            updateLchStatus(lchSub);
            setActiveSubscription(lchSub);
            manageDsdaInCallTones(false);
        }
        boolean isLchEnabled = (mLchStatus.get(subId) == null) ? false : mLchStatus.get(subId);
        if ((subId != null) && isLchEnabled) {
            Call activecall = getFirstCallWithState(subId, CallState.RINGING, CallState.DIALING,
                    CallState.ACTIVE, CallState.ON_HOLD);
            Log.d(this,"activecall: " + activecall);
            if (activecall == null) {
                mLchStatus.put(subId, false);
                manageDsdaInCallTones(false);
            }
        }
        // Emergency MO call is still pending and current active call is
        // disconnected succesfully. So initiating pending Emergency call
        // now and clearing both pending and Disconnectcalls.
        if (mPendingMOEmerCall != null && mDisconnectingCall == call) {
            addCall(mPendingMOEmerCall);
            mPendingMOEmerCall.startCreateConnection(mPhoneAccountRegistrar);
            clearPendingMOEmergencyCall();
        }
    }

    /**
     * Removes an existing disconnected call, and notifies the in-call app.
     */
    void markCallAsRemoved(Call call) {
        removeCall(call);
        if (!hasAnyCalls()) {
            updateLchStatus(null);
            setActiveSubscription(null);
            manageDsdaInCallTones(false);
        }
        if (mLocallyDisconnectingCalls.contains(call)) {
            boolean isChildCall = call.isChildCall();
            Log.v(this, "markCallAsRemoved: isChildCall = "
                + isChildCall + "call -> %s", call);
            mLocallyDisconnectingCalls.remove(call);
            Call foregroundCall = mCallAudioManager.getPossiblyHeldForegroundCall();
            if (!isChildCall && foregroundCall != null
                    && foregroundCall.getState() == CallState.ON_HOLD) {
                foregroundCall.unhold();
            }
        }
    }

    /**
     * Cleans up any calls currently associated with the specified connection service when the
     * service binder disconnects unexpectedly.
     *
     * @param service The connection service that disconnected.
     */
    void handleConnectionServiceDeath(ConnectionServiceWrapper service) {
        if (service != null) {
            for (Call call : mCalls) {
                if (call.getConnectionService() == service) {
                    if (call.getState() != CallState.DISCONNECTED) {
                        markCallAsDisconnected(call, new DisconnectCause(DisconnectCause.ERROR));
                    }
                    markCallAsRemoved(call);
                }
            }
        }
    }

    /**
     * Determines if the {@link CallsManager} has any non-external calls.
     *
     * @return {@code True} if there are any non-external calls, {@code false} otherwise.
     */
    boolean hasAnyCalls() {
        if (mCalls.isEmpty()) {
            return false;
        }

        for (Call call : mCalls) {
            if (!call.isExternalCall()) {
                return true;
            }
        }
        return false;
    }

    boolean hasActiveOrHoldingCall() {
        return getFirstCallWithState(CallState.ACTIVE, CallState.ON_HOLD) != null;
    }

    boolean hasActiveOrHoldingCall(String sub) {
        return (getFirstCallWithState(sub, CallState.ACTIVE, CallState.ON_HOLD) != null);
    }

    boolean hasRingingCall() {
        return getFirstCallWithState(CallState.RINGING) != null;
    }

    boolean onMediaButton(int type) {
        if (hasAnyCalls()) {
            if (HeadsetMediaButton.SHORT_PRESS == type) {
                Call ringingCall = getFirstCallWithState(CallState.RINGING);
                if (ringingCall == null) {
                    mCallAudioManager.toggleMute();
                    return true;
                } else {
                    ringingCall.answer(ringingCall.getVideoState());
                    return true;
                }
            } else if (HeadsetMediaButton.LONG_PRESS == type) {
                Log.d(this, "handleHeadsetHook: longpress -> hangup");
                Call callToHangup = getFirstCallWithState(
                        CallState.RINGING, CallState.DIALING, CallState.ACTIVE, CallState.ON_HOLD);
                if (callToHangup != null) {
                    callToHangup.disconnect();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if telecom supports adding another top-level call.
     */
    boolean canAddCall() {
        boolean isDeviceProvisioned = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (!isDeviceProvisioned) {
            Log.d(TAG, "Device not provisioned, canAddCall is false.");
            return false;
        }

        if (getFirstCallWithState(OUTGOING_CALL_STATES) != null) {
            return false;
        }

        int count = 0;
        for (Call call : mCalls) {
            if (call.isEmergencyCall()) {
                // We never support add call if one of the calls is an emergency call.
                return false;
            } else if (call.getParentCall() == null) {
                count++;
            }

            // We do not check states for canAddCall. We treat disconnected calls the same
            // and wait until they are removed instead. If we didn't count disconnected calls,
            // we could put InCallServices into a state where they are showing two calls but
            // also support add-call. Technically it's right, but overall looks better (UI-wise)
            // and acts better if we wait until the call is removed.
            if (TelephonyManager.getDefault().getMultiSimConfiguration()
                    == TelephonyManager.MultiSimVariants.DSDA) {
                if (count >= MAXIMUM_DSDA_TOP_LEVEL_CALLS) {
                    return false;
                }
            } else if (count >= MAXIMUM_TOP_LEVEL_CALLS) {
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    public Call getRingingCall() {
        return getFirstCallWithState(CallState.RINGING);
    }

    @VisibleForTesting
    public Call getActiveCall() {
        return getFirstCallWithState(CallState.ACTIVE);
    }

    Call getDialingCall() {
        return getFirstCallWithState(CallState.DIALING);
    }

    @VisibleForTesting
    public Call getHeldCall() {
        return getFirstCallWithState(CallState.ON_HOLD);
    }

    @VisibleForTesting
    public int getNumHeldCalls() {
        int count = 0;
        for (Call call : mCalls) {
            if (call.getParentCall() == null && call.getState() == CallState.ON_HOLD) {
                count++;
            }
        }
        return count;
    }

    @VisibleForTesting
    public Call getOutgoingCall() {
        return getFirstCallWithState(OUTGOING_CALL_STATES);
    }

    @VisibleForTesting
    public Call getFirstCallWithState(int... states) {
        return getFirstCallWithState((Call) null, states);
    }

    /**
     * Returns the first call that it finds with the given states. The states are treated as having
     * priority order so that any call with the first state will be returned before any call with
     * states listed later in the parameter list.
     *
     * @param callToSkip Call that this method should skip while searching
     */
    Call getFirstCallWithState(Call callToSkip, int... states) {
        for (int currentState : states) {
            // check the foreground first
            Call foregroundCall = getForegroundCall();
            if (foregroundCall != null && foregroundCall.getState() == currentState) {
                return foregroundCall;
            }

            for (Call call : mCalls) {
                if (Objects.equals(callToSkip, call)) {
                    continue;
                }

                // Only operate on top-level calls
                if (call.getParentCall() != null) {
                    continue;
                }

                if (currentState == call.getState()) {
                    return call;
                }
            }
        }
        return null;
    }

    /**
     * Returns the first call that it finds with the given states for given subscription.
     * the states are treated as having priority order so that any call with the first
     * state will be returned before any call with states listed later in the parameter list.
     *
     * @param subId check calls only on this subscription
     * @param callToSkip Call that this method should skip while searching
     */
    Call getFirstCallWithState(String subId, Call callToSkip, int... states) {
        for (int currentState : states) {
            // check the foreground first
            Call foregroundCall = getForegroundCall();
            if (foregroundCall != null && foregroundCall.getState() == currentState
                    && foregroundCall.getTargetPhoneAccount() != null
                    && isSamePhAccIdOrSipId(foregroundCall.getTargetPhoneAccount().getId(), subId)) {
                return foregroundCall;
            }

            for (Call call : mCalls) {
                if (Objects.equals(callToSkip, call)) {
                    continue;
                }

                // Only operate on top-level calls
                if (call.getParentCall() != null) {
                    continue;
                }

                if (currentState == call.getState() && call.getTargetPhoneAccount() != null
                        && isSamePhAccIdOrSipId(call.getTargetPhoneAccount().getId(), subId)) {
                    return call;
                }
            }
        }
        return null;
    }

    Call createConferenceCall(
            String callId,
            PhoneAccountHandle phoneAccount,
            ParcelableConference parcelableConference) {

        // If the parceled conference specifies a connect time, use it; otherwise default to 0,
        // which is the default value for new Calls.
        long connectTime =
                parcelableConference.getConnectTimeMillis() ==
                        Conference.CONNECT_TIME_NOT_SPECIFIED ? 0 :
                        parcelableConference.getConnectTimeMillis();

        Call call = new Call(
                callId,
                mContext,
                this,
                mLock,
                mConnectionServiceRepository,
                mContactsAsyncHelper,
                mCallerInfoAsyncQueryFactory,
                null /* handle */,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccount,
                Call.CALL_DIRECTION_UNDEFINED /* callDirection */,
                false /* forceAttachToExistingConnection */,
                true /* isConference */,
                connectTime);

        setCallState(call, Call.getStateFromConnectionState(parcelableConference.getState()),
                "new conference call");
        call.setConnectionCapabilities(parcelableConference.getConnectionCapabilities());
        call.setConnectionProperties(parcelableConference.getConnectionProperties());
        call.setVideoState(parcelableConference.getVideoState());
        call.setVideoProvider(parcelableConference.getVideoProvider());
        call.setStatusHints(parcelableConference.getStatusHints());
        call.putExtras(Call.SOURCE_CONNECTION_SERVICE, parcelableConference.getExtras());

        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        addCall(call);
        return call;
    }

    /**
     * @return the call state currently tracked by {@link PhoneStateBroadcaster}
     */
    int getCallState() {
        return mPhoneStateBroadcaster.getCallState();
    }

    /**
     * Retrieves the {@link PhoneAccountRegistrar}.
     *
     * @return The {@link PhoneAccountRegistrar}.
     */
    PhoneAccountRegistrar getPhoneAccountRegistrar() {
        return mPhoneAccountRegistrar;
    }

    /**
     * Retrieves the {@link MissedCallNotifier}
     * @return The {@link MissedCallNotifier}.
     */
    MissedCallNotifier getMissedCallNotifier() {
        return mMissedCallNotifier;
    }

    /**
     * Reject an incoming call and manually add it to the Call Log.
     * @param incomingCall Incoming call that has been rejected
     */
    private void rejectCallAndLog(Call incomingCall) {
        incomingCall.reject(false, null);
        // Since the call was not added to the list of calls, we have to call the missed
        // call notifier and the call logger manually.
        // Do we need missed call notification for direct to Voicemail calls?
        mCallLogManager.logCall(incomingCall, Calls.MISSED_TYPE,
                true /*showNotificationForMissedCall*/);
    }

    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        Trace.beginSection("addCall");
        Log.v(this, "addCall(%s)", call);
        call.addListener(this);
        mCalls.add(call);

        // Specifies the time telecom finished routing the call. This is used by the dialer for
        // analytics.
        Bundle extras = call.getIntentExtras();
        extras.putLong(TelecomManager.EXTRA_CALL_TELECOM_ROUTING_END_TIME_MILLIS,
                SystemClock.elapsedRealtime());

        updateCallsManagerState();
        // onCallAdded for calls which immediately take the foreground (like the first call).
        for (CallsManagerListener listener : mListeners) {
            if (Log.SYSTRACE_DEBUG) {
                Trace.beginSection(listener.getClass().toString() + " addCall");
            }
            listener.onCallAdded(call);
            if (Log.SYSTRACE_DEBUG) {
                Trace.endSection();
            }
        }
        Trace.endSection();
    }

    private void removeCall(Call call) {
        Trace.beginSection("removeCall");
        Log.v(this, "removeCall(%s)", call);

        call.setParentCall(null);  // need to clean up parent relationship before destroying.
        call.removeListener(this);
        call.clearConnectionService();

        boolean shouldNotify = false;
        if (mCalls.contains(call)) {
            mCalls.remove(call);
            shouldNotify = true;
        }

        call.destroy();

        // Only broadcast changes for calls that are being tracked.
        if (shouldNotify) {
            updateCallsManagerState();
            for (CallsManagerListener listener : mListeners) {
                if (Log.SYSTRACE_DEBUG) {
                    Trace.beginSection(listener.getClass().toString() + " onCallRemoved");
                }
                listener.onCallRemoved(call);
                if (Log.SYSTRACE_DEBUG) {
                    Trace.endSection();
                }
            }
        }
        Trace.endSection();
    }

    /**
     * Sets the specified state on the specified call.
     *
     * @param call The call.
     * @param newState The new state of the call.
     */
    private void setCallState(Call call, int newState, String tag) {
        if (call == null) {
            return;
        }
        int oldState = call.getState();
        Log.i(this, "setCallState %s -> %s, call: %s", CallState.toString(oldState),
                CallState.toString(newState), call);
        if (newState != oldState) {
            // Unfortunately, in the telephony world the radio is king. So if the call notifies
            // us that the call is in a particular state, we allow it even if it doesn't make
            // sense (e.g., STATE_ACTIVE -> STATE_RINGING).
            // TODO: Consider putting a stop to the above and turning CallState
            // into a well-defined state machine.
            // TODO: Define expected state transitions here, and log when an
            // unexpected transition occurs.
            call.setState(newState, tag);
            maybeShowErrorDialogOnDisconnect(call);

            Trace.beginSection("onCallStateChanged");
            // Only broadcast state change for calls that are being tracked.
            if (mCalls.contains(call)) {
                updateCallsManagerState();
                for (CallsManagerListener listener : mListeners) {
                    if (Log.SYSTRACE_DEBUG) {
                        Trace.beginSection(listener.getClass().toString() + " onCallStateChanged");
                    }
                    listener.onCallStateChanged(call, oldState, newState);
                    if (Log.SYSTRACE_DEBUG) {
                        Trace.endSection();
                    }
                }
            }
            Trace.endSection();
        }
        manageDsdaInCallTones(false);
    }

    private void updateCanAddCall() {
        boolean newCanAddCall = canAddCall();
        if (newCanAddCall != mCanAddCall) {
            mCanAddCall = newCanAddCall;
            for (CallsManagerListener listener : mListeners) {
                if (Log.SYSTRACE_DEBUG) {
                    Trace.beginSection(listener.getClass().toString() + " updateCanAddCall");
                }
                listener.onCanAddCallChanged(mCanAddCall);
                if (Log.SYSTRACE_DEBUG) {
                    Trace.endSection();
                }
            }
        }
    }

    private void updateCallsManagerState() {
        updateCanAddCall();
    }

    private boolean isPotentialMMICode(Uri handle) {
        return (handle != null && handle.getSchemeSpecificPart() != null
                && handle.getSchemeSpecificPart().contains("#"));
    }

    /**
     * Determines if a dialed number is potentially an In-Call MMI code.  In-Call MMI codes are
     * MMI codes which can be dialed when one or more calls are in progress.
     * <P>
     * Checks for numbers formatted similar to the MMI codes defined in:
     * {@link com.android.internal.telephony.Phone#handleInCallMmiCommands(String)}
     *
     * @param handle The URI to call.
     * @return {@code True} if the URI represents a number which could be an in-call MMI code.
     */
    private boolean isPotentialInCallMMICode(Uri handle) {
        if (handle != null && handle.getSchemeSpecificPart() != null &&
                handle.getScheme() != null &&
                handle.getScheme().equals(PhoneAccount.SCHEME_TEL)) {

            String dialedNumber = handle.getSchemeSpecificPart();
            return (dialedNumber.equals("0") ||
                    (dialedNumber.startsWith("1") && dialedNumber.length() <= 2) ||
                    (dialedNumber.startsWith("2") && dialedNumber.length() <= 2) ||
                    dialedNumber.equals("3") ||
                    dialedNumber.equals("4") ||
                    dialedNumber.equals("5"));
        }
        return false;
    }

    private int getNumCallsWithState(int... states) {
        int count = 0;
        for (int state : states) {
            for (Call call : mCalls) {
                if (call.getParentCall() == null && call.getState() == state) {
                    count++;
                }
            }
        }
        return count;
    }

    private int getNumCallsWithState(String subId, int... states) {
        int count = 0;
        for (int state : states) {
            for (Call call : mCalls) {
                if (call.getParentCall() == null && call.getState() == state
                        && call.getTargetPhoneAccount() != null
                        && isSamePhAccIdOrSipId(call.getTargetPhoneAccount().getId(), subId)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean hasMaximumLiveCalls() {
        return MAXIMUM_LIVE_CALLS <= getNumCallsWithState(LIVE_CALL_STATES);
    }

    private boolean hasMaximumLiveCalls(String subId) {
        return MAXIMUM_LIVE_CALLS <= getNumCallsWithState(subId, LIVE_CALL_STATES);
    }

    private boolean hasMaximumHoldingCalls() {
        return MAXIMUM_HOLD_CALLS <= getNumCallsWithState(CallState.ON_HOLD);
    }

    private boolean hasMaximumHoldingCalls(String subId) {
        return MAXIMUM_HOLD_CALLS <= getNumCallsWithState(subId, CallState.ON_HOLD);
    }

    private boolean hasMaximumRingingCalls() {
        return MAXIMUM_RINGING_CALLS <= getNumCallsWithState(CallState.RINGING);
    }

    private boolean hasMaximumRingingCalls(String subId) {
        return MAXIMUM_RINGING_CALLS <= getNumCallsWithState(subId, CallState.RINGING);
    }

    private boolean hasMaximumOutgoingCalls() {
        return MAXIMUM_OUTGOING_CALLS <= getNumCallsWithState(OUTGOING_CALL_STATES);
    }

    private boolean hasMaximumDialingCalls() {
        return MAXIMUM_DIALING_CALLS <= getNumCallsWithState(CallState.DIALING);
    }

    /**
     * Returns true if there is an Emergency Call in Call list.
     */
    private boolean IsEmergencyCallInProgress() {
        for (Call call : mCalls) {
            if (call.isEmergencyCall()) {
                // We never support add call if one of the calls is an emergency call.
                return true;
            }
        }
        return false;
    }

    private boolean makeRoomForOutgoingCall(Call call, boolean isEmergency) {
        if (isEmergency && IsEmergencyCallInProgress()) {
            Log.i(this, "emergency call is progress so no room for new E Call");
            return false;
        }
        if (TelephonyManager.getDefault().getMultiSimConfiguration()
                == TelephonyManager.MultiSimVariants.DSDA) {
            return makeRoomForOutgoingCallForDsda(call, isEmergency);
        }
        // Reject If there is any Incoming Call while initiating an
        // an Emergency Call.
        if (isEmergency && hasMaximumRingingCalls()) {
            Call rinigingCall = getRingingCall();
            rinigingCall.reject(false, null);
        }
        if (hasMaximumLiveCalls()) {
            // NOTE: If the amount of live calls changes beyond 1, this logic will probably
            // have to change.
            Call liveCall = getFirstCallWithState(LIVE_CALL_STATES);
            Log.i(this, "makeRoomForOutgoingCall call = " + call + " livecall = " +
                   liveCall);

            if (call == liveCall) {
                // If the call is already the foreground call, then we are golden.
                // This can happen after the user selects an account in the SELECT_PHONE_ACCOUNT
                // state since the call was already populated into the list.
                return true;
            }

            if (hasMaximumOutgoingCalls()) {
                Call outgoingCall = getFirstCallWithState(OUTGOING_CALL_STATES);
                if (isEmergency && !outgoingCall.isEmergencyCall()) {
                    // Disconnect the current outgoing call if it's not an emergency call. If the
                    // user tries to make two outgoing calls to different emergency call numbers,
                    // we will try to connect the first outgoing call.
                    call.getAnalytics().setCallIsAdditional(true);
                    outgoingCall.getAnalytics().setCallIsInterrupted(true);
                    outgoingCall.disconnect();
                    // buffer this call in to mPendingMOEmerCall and do not initiate this call
                    // until the current outgoing call mDisconnectingCall is disconnected
                    // successfully. Emergency Call would be initiated upon receiving the
                    // Disconnection response from lower layers.
                    mDisconnectingCall = outgoingCall;
                    mPendingMOEmerCall = call;
                    return true;
                }
                if (outgoingCall.getState() == CallState.SELECT_PHONE_ACCOUNT) {
                    // If there is an orphaned call in the {@link CallState#SELECT_PHONE_ACCOUNT}
                    // state, just disconnect it since the user has explicitly started a new call.
                    call.getAnalytics().setCallIsAdditional(true);
                    outgoingCall.getAnalytics().setCallIsInterrupted(true);
                    outgoingCall.disconnect();
                    return true;
                }
                return false;
            }

            if (hasMaximumHoldingCalls()) {
                // There is no more room for any more calls, unless it's an emergency.
                if (isEmergency) {
                    // Kill the current active call, this is easier then trying to disconnect a
                    // holding call and hold an active call.
                    call.getAnalytics().setCallIsAdditional(true);
                    liveCall.getAnalytics().setCallIsInterrupted(true);
                    liveCall.disconnect();
                    // buffer this call in to mPendingMOEmerCall and do not initiate this call
                    // until the current live call "mDisconnectingCall" is disconnected
                    // successfully. Emergency Call would be initiated upon receiving the
                    // Disconnection response from lower layers.
                    mDisconnectingCall = liveCall;
                    mPendingMOEmerCall = call;
                    return true;
                }
                return false;  // No more room!
            }

            // We have room for at least one more holding call at this point.

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
                liveCallPhoneAccount = getFirstChildPhoneAccount(liveCall);
                Log.i(this, "makeRoomForOutgoingCall: using child call PhoneAccount = " +
                        liveCallPhoneAccount);
            }

            // First thing, if we are trying to make a call with the same phone account as the live
            // call, then allow it so that the connection service can make its own decision about
            // how to handle the new call relative to the current one.
            if (Objects.equals(liveCallPhoneAccount, call.getTargetPhoneAccount())) {
                Log.i(this, "makeRoomForOutgoingCall: phoneAccount matches.");
                call.getAnalytics().setCallIsAdditional(true);
                liveCall.getAnalytics().setCallIsInterrupted(true);
                return true;
            } else if (call.getTargetPhoneAccount() == null) {
                // Without a phone account, we can't say reliably that the call will fail.
                // If the user chooses the same phone account as the live call, then it's
                // still possible that the call can be made (like with CDMA calls not supporting
                // hold but they still support adding a call by going immediately into conference
                // mode). Return true here and we'll run this code again after user chooses an
                // account.
                return true;
            }

            // Try to hold the live call before attempting the new outgoing call.
            if (liveCall.can(Connection.CAPABILITY_HOLD)) {
                Log.i(this, "makeRoomForOutgoingCall: holding live call.");
                call.getAnalytics().setCallIsAdditional(true);
                liveCall.getAnalytics().setCallIsInterrupted(true);
                liveCall.hold();
                return true;
            }

            // The live call cannot be held so we're out of luck here.  There's no room.
            return false;
        }
        return true;
    }

    private boolean makeRoomForOutgoingCallForDsda(Call call, boolean isEmergency) {
        if (isEmergency || (call.getState() == CallState.SELECT_PHONE_ACCOUNT)) {
            return true;
        }

        PhoneAccountHandle phAcc = call.getTargetPhoneAccount();
        if (phAcc == null) {
            if (getNumCallsWithState(LIVE_CALL_STATES) == MAXIMUM_DSDA_LIVE_CALLS
                    && getNumCallsWithState(CallState.ON_HOLD) == MAXIMUM_DSDA_HOLD_CALLS) {
                return false;
            } else {
                return true;
            }
        }
        if (hasMaximumLiveCalls(phAcc.getId())) {
            // NOTE: If the amount of live calls changes beyond 1, this logic will probably
            // have to change.
            Call liveCall = getFirstCallWithState(phAcc.getId(), call, LIVE_CALL_STATES);

            if (hasMaximumHoldingCalls(phAcc.getId())) {
                // There is no more room for any more calls, unless it's an emergency.
                return false;  // No more room!
            }
            if (Objects.equals(liveCall.getTargetPhoneAccount(), call.getTargetPhoneAccount())) {
                return true;
            }
            // Try to hold the live call before attempting the new outgoing call.
            if (liveCall.can(Connection.CAPABILITY_HOLD)) {
                liveCall.hold();
                return true;
            }

            // The live call cannot be held so we're out of luck here.  There's no room.
            return false;
        }
        return true;
    }

    /**
     * Given a call, find the first non-null phone account handle of its children.
     *
     * @param parentCall The parent call.
     * @return The first non-null phone account handle of the children, or {@code null} if none.
     */
    private PhoneAccountHandle getFirstChildPhoneAccount(Call parentCall) {
        for (Call childCall : parentCall.getChildCalls()) {
            PhoneAccountHandle childPhoneAccount = childCall.getTargetPhoneAccount();
            if (childPhoneAccount != null) {
                return childPhoneAccount;
            }
        }
        return null;
    }

    /**
     * Checks to see if the call should be on speakerphone and if so, set it.
     */
    private void maybeMoveToSpeakerPhone(Call call) {
        if (call.getStartWithSpeakerphoneOn()) {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER);
            call.setStartWithSpeakerphoneOn(false);
        }
    }

    private void maybeMoveToEarpiece(Call call) {
        if (!call.getStartWithSpeakerphoneOn() && !mWiredHeadsetManager.isPluggedIn() &&
                !mBluetoothManager.isBluetoothAvailable()) {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE);
        }
    }

    /**
     * Creates a new call for an existing connection.
     *
     * @param callId The id of the new call.
     * @param connection The connection information.
     * @return The new call.
     */
    Call createCallForExistingConnection(String callId, ParcelableConnection connection) {
        Call call = new Call(
                callId,
                mContext,
                this,
                mLock,
                mConnectionServiceRepository,
                mContactsAsyncHelper,
                mCallerInfoAsyncQueryFactory,
                connection.getHandle() /* handle */,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                connection.getPhoneAccount(), /* targetPhoneAccountHandle */
                Call.CALL_DIRECTION_UNDEFINED /* callDirection */,
                false /* forceAttachToExistingConnection */,
                false /* isConference */,
                connection.getConnectTimeMillis() /* connectTimeMillis */);

        call.initAnalytics();
        call.getAnalytics().setCreatedFromExistingConnection(true);

        setCallState(call, Call.getStateFromConnectionState(connection.getState()),
                "existing connection");
        call.setConnectionCapabilities(connection.getConnectionCapabilities());
        call.setConnectionProperties(connection.getConnectionProperties());
        call.setCallerDisplayName(connection.getCallerDisplayName(),
                connection.getCallerDisplayNamePresentation());

        call.addListener(this);
        addCall(call);

        return call;
    }

    /**
     * @return A new unique telecom call Id.
     */
    private String getNextCallId() {
        synchronized(mLock) {
            return TELECOM_CALL_ID_PREFIX + (++mCallId);
        }
    }

    /**
     * Callback when foreground user is switched. We will reload missed call in all profiles
     * including the user itself. There may be chances that profiles are not started yet.
     */
    void onUserSwitch(UserHandle userHandle) {
        mCurrentUserHandle = userHandle;
        mMissedCallNotifier.setCurrentUserHandle(userHandle);
        final UserManager userManager = UserManager.get(mContext);
        List<UserInfo> profiles = userManager.getEnabledProfiles(userHandle.getIdentifier());
        for (UserInfo profile : profiles) {
            reloadMissedCallsOfUser(profile.getUserHandle());
        }
    }

    /**
     * Because there may be chances that profiles are not started yet though its parent user is
     * switched, we reload missed calls of profile that are just started here.
     */
    void onUserStarting(UserHandle userHandle) {
        if (UserUtil.isProfile(mContext, userHandle)) {
            reloadMissedCallsOfUser(userHandle);
        }
    }

    private void reloadMissedCallsOfUser(UserHandle userHandle) {
        mMissedCallNotifier.reloadFromDatabase(
                mLock, this, mContactsAsyncHelper, mCallerInfoAsyncQueryFactory, userHandle);
    }

    /**
     * Dumps the state of the {@link CallsManager}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
        if (mCalls != null) {
            pw.println("mCalls: ");
            pw.increaseIndent();
            for (Call call : mCalls) {
                pw.println(call);
            }
            pw.decreaseIndent();
        }

        if (mCallAudioManager != null) {
            pw.println("mCallAudioManager:");
            pw.increaseIndent();
            mCallAudioManager.dump(pw);
            pw.decreaseIndent();
        }

        if (mTtyManager != null) {
            pw.println("mTtyManager:");
            pw.increaseIndent();
            mTtyManager.dump(pw);
            pw.decreaseIndent();
        }

        if (mInCallController != null) {
            pw.println("mInCallController:");
            pw.increaseIndent();
            mInCallController.dump(pw);
            pw.decreaseIndent();
        }

        if (mConnectionServiceRepository != null) {
            pw.println("mConnectionServiceRepository:");
            pw.increaseIndent();
            mConnectionServiceRepository.dump(pw);
            pw.decreaseIndent();
        }
    }

    /**
    * For some disconnected causes, we show a dialog when it's a mmi code or potential mmi code.
    *
    * @param call The call.
    */
    private void maybeShowErrorDialogOnDisconnect(Call call) {
        if (call.getState() == CallState.DISCONNECTED && (isPotentialMMICode(call.getHandle())
                || isPotentialInCallMMICode(call.getHandle()))) {
            DisconnectCause disconnectCause = call.getDisconnectCause();
            if (!TextUtils.isEmpty(disconnectCause.getDescription()) && (disconnectCause.getCode()
                    == DisconnectCause.ERROR)) {
                Intent errorIntent = new Intent(mContext, ErrorDialogActivity.class);
                errorIntent.putExtra(ErrorDialogActivity.ERROR_MESSAGE_STRING_EXTRA,
                        disconnectCause.getDescription());
                errorIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(errorIntent, UserHandle.CURRENT);
            }
        }
    }

    private void setIntentExtrasAndStartTime(Call call, Bundle extras) {
      // Create our own instance to modify (since extras may be Bundle.EMPTY)
      extras = new Bundle(extras);

      // Specifies the time telecom began routing the call. This is used by the dialer for
      // analytics.
      extras.putLong(TelecomManager.EXTRA_CALL_TELECOM_ROUTING_START_TIME_MILLIS,
              SystemClock.elapsedRealtime());

      call.setIntentExtras(extras);
    }

    private final Handler mLchHandler = new LchHandler();
    private final class LchHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PHONE_START_DSDA_INCALL_TONE:
                    Log.d(this, "Start DSDA incall tones...");
                    startDsdaInCallTones();
                    break;
                case LCH_PLAY_DTMF:
                    playLchDtmf();
                    break;
                case LCH_STOP_DTMF:
                    stopLchDtmf();
                    break;
                case SET_LOCAL_CALL_HOLD:
                    updateLchStatusToRil((String) msg.obj);
                    break;
            }
        }
    }

    void switchToOtherActiveSub(String subId) {
        if (TelephonyManager.getDefault().getMultiSimConfiguration()
                != TelephonyManager.MultiSimVariants.DSDA) {
            return;
        }
        Log.d(this, "switchToOtherActiveSub sub:" + subId);
        setActiveSubscription(subId);
        updateLchStatus(subId);
        manageDsdaInCallTones(true);
    }

    String getActiveSubscription() {
        return mActiveSub;
    }

    synchronized void setActiveSubscription(String subId) {
        if (TelephonyManager.getDefault().getMultiSimConfiguration()
                != TelephonyManager.MultiSimVariants.DSDA) {
            return;
        }
        Log.d(this, "setActiveSubscription = " + subId);
        if (subId == null) {
            mActiveSub = null;
            return;
        }
        if (subId.equals(mActiveSub)) {
            Log.d(this, "setActiveSubscription not changed " + subId + " mActiveSub:" + mActiveSub);
            return;
        }
        mActiveSub = subId;
        Log.d(this, "setActiveSubscription changed " + mActiveSub);
        for (Call call : mCalls) {
            PhoneAccountHandle ph = call.getTargetPhoneAccount();
            if (ph != null && subId.equals(ph.getId())) {
                Bundle extras = new Bundle();
                extras.putBoolean(ACTIVE_SUBSCRIPTION, true);
                call.putExtras(Call.SOURCE_CONNECTION_SERVICE, extras);
            } else {
                Bundle extras = new Bundle();
                extras.putBoolean(ACTIVE_SUBSCRIPTION, false);
                call.putExtras(Call.SOURCE_CONNECTION_SERVICE, extras);
            }
        }
    }

    void manageDsdaInCallTones(boolean isSubSwitch) {
        Log.d(this, " entered manageDsdaInCallTones ");

        // If there is no background active subscription available, stop playing the tones.
        // Do not start/stop LCH/SCH tones when phone is in RINGING state.
        if (getLchSub() != null && !hasRingingCall()) {
            //If sub switch happens re-start the tones with a delay of 100msec.
            if (isSubSwitch) {
                Log.d(this, " manageDsdaInCallTones: re-start playing tones, lch sub = "
                        + getLchSub());
                reStartDsdaInCallTones();
            } else {
                Log.d(this, " entered manageDsdaInCallTones ");
                startDsdaInCallTones();
            }
        } else if (getLchSub() == null) {
            // if there is no sub in Lch state, then stop playing the tones
            stopMSimInCallTones();
        }
    }

    private void reStartDsdaInCallTones() {
        Log.d(this, " reStartDsdaInCallTones");
        stopMSimInCallTones();
        /* Remove any pending PHONE_START_DSDA_INCALL_TONE messages from queue */
        mLchHandler.removeMessages(PHONE_START_DSDA_INCALL_TONE);
        Message message = Message.obtain(mLchHandler, PHONE_START_DSDA_INCALL_TONE);
        mLchHandler.sendMessageDelayed(message, 100);
    }

    /**
     * Returns the first call that it finds with the given states for given subscription.
     * The states are treated as having priority order so that any call with the first
     * state will be returned before any call with states listed later in the parameter list.
     */
    Call getFirstCallWithState(String sub, int... states) {
        for (int currentState : states) {
            // check the foreground first
            Call foregroundCall = getForegroundCall();
            if (foregroundCall != null && foregroundCall.getState() == currentState
                    && (foregroundCall.getTargetPhoneAccount() != null)
                    && isSamePhAccIdOrSipId(foregroundCall.getTargetPhoneAccount().getId(),
                    sub)) {
                return foregroundCall;
            }

            for (Call call : mCalls) {
                if ((currentState == call.getState()) &&
                        (call.getTargetPhoneAccount() != null) &&
                        (isSamePhAccIdOrSipId(call.getTargetPhoneAccount().getId(), sub))) {
                    return call;
                }
            }
        }
        return null;
    }
    /**
     * Check whether any other sub is in active state other than
     * provided subscription, if yes return the other active sub.
     * @return subscription which is active.
     */
    private String getOtherActiveSub(String subscription) {
        String otherSub = null;;
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar()
                .getSimPhoneAccountsOfCurrentUser()) {
            String sub = ph.getId();
            if (!sub.equals(subscription) && getFirstCallWithState(sub, CallState.CONNECTING,
                    CallState.DIALING, CallState.ACTIVE) != null) {
                otherSub = sub;
            }
        }
        return otherSub;
    }

    private String getConversationSub() {
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar()
                .getSimPhoneAccountsOfCurrentUser()) {
            String sub = ph.getId();
            if ((mLchStatus.get(sub) == null || mLchStatus.get(sub) == false)
                    && getFirstCallWithState(sub, CallState.CONNECTING,
                    CallState.DIALING, CallState.ACTIVE) != null) {
                return sub;
            }
        }
        return null;
    }

    private String getLchSub() {
        Iterator<String> keySetIterator = mLchStatus.keySet().iterator();
        while(keySetIterator.hasNext()){
            String sub = keySetIterator.next();
            if (mLchStatus.get(sub)!= null && mLchStatus.get(sub)) {
                return sub;
            }
        }
        return null;
    }

    private void playLchDtmf() {
        if (mLchSub != null || mLchHandler.hasMessages(LCH_PLAY_DTMF)) {
            // Ignore any redundant requests to start playing tones
            return;
        }
        mLchSub = getLchSub();
        Log.d(this, " playLchDtmf... lch sub " + mLchSub);
        if (mLchSub == null) return;
        removeAnyPendingDtmfMsgs();
        char c = mContext.getResources()
                .getString(R.string.lch_dtmf_key).charAt(0);
        Call call = getNonRingingLiveCall(mLchSub);
        if (call == null) {
            mLchSub = null;
            return;
        }
        call.playDtmfTone(c);
        // Keep playing LCH DTMF tone to remote party on LCH call, with periodicity
        // "LCH_DTMF_PERIODICITY" until call moves out of LCH.
        mLchHandler.sendMessageDelayed(Message.obtain(mLchHandler, LCH_PLAY_DTMF),
                LCH_DTMF_PERIODICITY);
        mLchHandler.sendMessageDelayed(Message.obtain(mLchHandler, LCH_STOP_DTMF), LCH_DTMF_PERIOD);
    }

    private Call getNonRingingLiveCall(String subId) {
        return getFirstCallWithState(subId, CallState.DIALING,
                CallState.ACTIVE, CallState.ON_HOLD);
    }

    private void stopLchDtmf() {
        if (mLchSub != null) {
            // Ignore any redundant requests to stop playing tones
            Call call = getNonRingingLiveCall(mLchSub);
            Log.d(this, " stopLchDtmf... call: " + call + " mLchSub:" + mLchSub);
            if (call == null) {
                mLchSub = null;
                return;
            }
            call.stopDtmfTone();
        }
        mLchSub = null;
    }

    private void startDsdaInCallTones() {
        if (mLocalCallReminderTonePlayer == null) {
            Log.d(this, " Play local call hold reminder tone ");
            mLocalCallReminderTonePlayer =
                    mPlayerFactory.createPlayer(InCallTonePlayer.TONE_HOLD_RECALL);
            mLocalCallReminderTonePlayer.start();
        }
        if (sSupervisoryCallHoldToneConfig.equals("dtmf")) {
            Log.d(this, " startDsdaInCallTones: Supervisory call hold tone over dtmf ");
            playLchDtmf();
        }
    }

    private void removeAnyPendingDtmfMsgs() {
        mLchHandler.removeMessages(LCH_PLAY_DTMF);
        mLchHandler.removeMessages(LCH_STOP_DTMF);
    }

    protected void stopMSimInCallTones() {
        if (mLocalCallReminderTonePlayer != null) {
            Log.d(this, " stopMSimInCallTones: local call hold reminder tone ");
            mLocalCallReminderTonePlayer.stopTone();
            mLocalCallReminderTonePlayer = null;
        }
        if (sSupervisoryCallHoldToneConfig.equals("dtmf")) {
            Log.d(this, " stopMSimInCallTones: stop SCH Dtmf call hold tone ");
            stopLchDtmf();
            /* Remove any previous dtmf nssages from queue */
            removeAnyPendingDtmfMsgs();
        }
    }

    private void updateLchStatus (String subId) {
        mLchHandler.obtainMessage(SET_LOCAL_CALL_HOLD, subId).sendToTarget();
    }

    /**
     * Update the local call hold state for all subscriptions
     * 1 -- if call on local hold, 0 -- if call is not on local hold
     *
     * @param subInConversation is the sub user is currently active in subsription.
     * so if this sub is in LCH, then bring that sub out of LCH.
     */
    private void updateLchStatusToRil(String subInConversation) {
        String removeFromLch = null;
        Log.d(this, "updateLchStatusToRil subInConversation: " + subInConversation);
        if ((subInConversation != null && (subInConversation.contains("sip")
                || subInConversation.contains("@")))
                || (TelephonyManager.getDefault()
                .getMultiSimConfiguration() != TelephonyManager.MultiSimVariants.DSDA)) {
            return;
        }
        for (PhoneAccountHandle ph : getPhoneAccountRegistrar()
                .getSimPhoneAccountsOfCurrentUser()) {
            String sub = ph.getId();
            if (sub != null && sub.contains("sip")) {
                Log.d(this, "update lch. Skipping account: " + sub);
                continue;
            }
            boolean lchState = false;
            Boolean isLchEnabled = mLchStatus.get(sub);
            isLchEnabled = (isLchEnabled == null) ? false : isLchEnabled;
            if (subInConversation != null && hasActiveOrHoldingCall(sub) &&
                    !sub.equals(subInConversation)) {
                // if sub is not conversation  sub and if it has an active
                // voice call then update lchStatus as Active
                lchState = true;
            }

            // Update state only if the new state is different
            if (lchState != isLchEnabled) {
                Call call = getNonRingingLiveCall(sub);
                Log.d(this, " setLocal Call Hold to  = " + lchState + " sub:" + sub);

                if (lchState) {
                    mLchStatus.put(sub, true);
                    setLocalCallHold(sub, true);
                } else {
                    removeFromLch = sub;
                    mLchStatus.put(sub, false);
                }
            }
        }
        if (removeFromLch != null) {
            // Ensure to send LCH disable request at last, to make sure that during switch
            // subscription, both subscriptions not to be in active(non-LCH) at any moment.
            setLocalCallHold(removeFromLch, false);
        }
    }

    void setDsdaAdapter() {
        if (mDsdaAdapter != null) {
            return;
        }
        mDsdaAdapter = new DsdaAdapter(this);
        IExtTelephony mExtTelephony = IExtTelephony.Stub.asInterface(ServiceManager
            .getService("extphone"));
        try {
            Log.d(this, "setDsdaAdapter");
            mExtTelephony.setDsdaAdapter(mDsdaAdapter);
        } catch (NullPointerException ex) {
            Log.d(this, "setDsdaAdapter" + ex);
        } catch (RemoteException ex) {
            Log.d(this, "setDsdaAdapter" + ex);
        }
    }

    private void setLocalCallHold(String subscriptionId, boolean enable) {
        IExtTelephony mExtTelephony = IExtTelephony.Stub.asInterface(ServiceManager
            .getService("extphone"));
        try {
            Log.d(this, "setLocalCallHold" + subscriptionId);
            mExtTelephony.setLocalCallHold(Integer.parseInt(subscriptionId), enable);
        } catch (NullPointerException ex) {
            Log.d(this, "setLocalCallHold" + ex);
        } catch (RemoteException ex) {
            Log.d(this, "setLocalCallHold" + ex);
        } catch (NumberFormatException ex) {
            Log.d(this, "setLocalCallHold" + ex);
        }
    }

    public void clearPendingMOEmergencyCall() {
        mPendingMOEmerCall = null;
        mDisconnectingCall = null;
    }
}
