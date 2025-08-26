/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.server.telecom.AudioRoute.BT_AUDIO_ROUTE_TYPES;
import static com.android.server.telecom.AudioRoute.DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE;
import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_SCO;
import static com.android.server.telecom.AudioRoute.TYPE_INVALID;
import static com.android.server.telecom.AudioRoute.TYPE_SPEAKER;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.sysprop.BluetoothProperties;
import android.telecom.CallAudioState;
import android.telecom.Log;
import android.telecom.Logging.Session;
import android.telecom.VideoProfile;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.metrics.ErrorStats;
import com.android.server.telecom.metrics.TelecomMetricsController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallAudioRouteController implements CallAudioRouteAdapter {
    public static class Factory {
        public CallAudioRouteController create(
                Context context, CallsManager callsManager,
                CallAudioManager.AudioServiceFactory audioServiceFactory,
                AudioRoute.Factory audioRouteFactory, WiredHeadsetManager wiredHeadsetManager,
                BluetoothRouteManager bluetoothRouteManager, StatusBarNotifier notifier,
                FeatureFlags featureFlags, TelecomMetricsController metricsController,
                AnomalyReporterAdapter anomalyReporterAdapter) {
            return new CallAudioRouteController(context,
                    callsManager,
                    audioServiceFactory,
                    audioRouteFactory,
                    wiredHeadsetManager,
                    bluetoothRouteManager,
                    notifier,
                    featureFlags,
                    metricsController,
                    anomalyReporterAdapter);
        }
    }
    private static final AudioRoute DUMMY_ROUTE = new AudioRoute(TYPE_INVALID, null, null);
    private static final UUID AUDIO_ROUTING_EXTERNAL_CHANGE_UUID =
            UUID.fromString("d9b38771-ff36-417b-8723-2363a870c702");
    private static final String AUDIO_ROUTING_EXTERNAL_CHANGE_MSG =
            "Call audio routing was changed externally by another app via the AudioManager APIs."
                    + "This should only be handled by Telecom.";
    private static final Map<Integer, Integer> ROUTE_MAP;
    static {
        ROUTE_MAP = new ArrayMap<>();
        ROUTE_MAP.put(TYPE_INVALID, 0);
        ROUTE_MAP.put(AudioRoute.TYPE_EARPIECE, CallAudioState.ROUTE_EARPIECE);
        ROUTE_MAP.put(AudioRoute.TYPE_WIRED, CallAudioState.ROUTE_WIRED_HEADSET);
        ROUTE_MAP.put(AudioRoute.TYPE_SPEAKER, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_DOCK, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_BUS, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_SCO, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_HA, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_LE, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_STREAMING, CallAudioState.ROUTE_STREAMING);
    }

    /** Valid values for the first argument for SWITCH_BASELINE_ROUTE */
    public static final int INCLUDE_BLUETOOTH_IN_BASELINE = 1;

    private final CallsManager mCallsManager;
    private final Context mContext;
    private AudioManager mAudioManager;
    private CallAudioManager mCallAudioManager;
    private final BluetoothRouteManager mBluetoothRouteManager;
    private final CallAudioManager.AudioServiceFactory mAudioServiceFactory;
    private final Handler mHandler;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private Set<AudioRoute> mAvailableRoutes;
    private Set<AudioRoute> mCallSupportedRoutes;
    private AudioRoute mCurrentRoute;
    private AudioRoute mEarpieceWiredRoute;
    private AudioRoute mSpeakerDockRoute;
    private AudioRoute mStreamingRoute;
    private Set<AudioRoute> mStreamingRoutes;
    private Map<AudioRoute, BluetoothDevice> mBluetoothRoutes;
    private Pair<Integer, String> mActiveBluetoothDevice;
    private Map<Integer, String> mActiveDeviceCache;
    private String mBluetoothAddressForRinging;
    private Map<Integer, AudioRoute> mTypeRoutes;
    private PendingAudioRoute mPendingAudioRoute;
    private AudioRoute.Factory mAudioRouteFactory;
    private StatusBarNotifier mStatusBarNotifier;
    private AudioManager.OnCommunicationDeviceChangedListener mCommunicationDeviceListener;
    private ExecutorService mAudioManagerListenerExecutor;
    private AudioManager.OnPreferredDevicesForStrategyChangedListener mPreferredDeviceListener;
    private AudioRoute mPreferredDeviceRoute;
    private FeatureFlags mFeatureFlags;
    private int mFocusType;
    private int mCallSupportedRouteMask = -1;
    private BluetoothDevice mScoAudioConnectedDevice;
    private BluetoothDevice mLastScoDisconnectedDevice;
    private boolean mAvailableRoutesUpdated;
    private boolean mUsePreferredDeviceStrategy;
    private AudioDeviceInfo mCurrentCommunicationDevice;
    private final Object mLock = new Object();
    private final TelecomSystem.SyncRoot mTelecomLock;
    private CountDownLatch mAudioOperationsCompleteLatch;
    private CountDownLatch mAudioActiveCompleteLatch;
    private AnomalyReporterAdapter mAnomalyReporterAdapter;
    private boolean mIsScoManagedByAudio;

    /** Receiver for added/removed device outputs that are reported by the audio fwk */
    public class AudioRoutesCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            Log.startSession("ARC.oADA");
            try {
                updateAudioRoutes(addedDevices, true);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            Log.startSession("ARC.oADR");
            try {
                updateAudioRoutes(devices, false);
            } finally {
                Log.endSession();
            }
        }

        private void updateAudioRoutes(AudioDeviceInfo[] devices, boolean addDevices) {
            Log.i(this, "updateAudioRoutes: add devices? %b", addDevices);
            for (AudioDeviceInfo deviceInfo: devices) {
                int audioRouteType = getAudioType(deviceInfo);
                Log.i(this, "updateAudioRoutes: audioDeviceInfo: %s, audioRouteType: %d",
                        deviceInfo, audioRouteType);
                if (!deviceInfo.isSink()) {
                    Log.i(this, "Ignore non sink device.");
                    continue;
                }
                // We should really only worry about handling earpiece and speaker. Bluetooth and
                // wired headset routes are already dynamically updated. This logic can be updated
                // once we support call audio route centralization.
                // SCO needs to check here as well when the SCO refactor feature is enabled.
                if ((!mIsScoManagedByAudio || audioRouteType != AudioRoute.TYPE_BLUETOOTH_SCO)
                        && (audioRouteType == TYPE_INVALID
                                || audioRouteType == AudioRoute.TYPE_WIRED
                                || BT_AUDIO_ROUTE_TYPES.contains(audioRouteType))) {
                    Log.i(this, "updateAudioRoutes: skipping route.");
                    continue;
                }
                if (addDevices) {
                    switch(audioRouteType) {
                        case AudioRoute.TYPE_SPEAKER:
                            createSpeakerRoute();
                            break;
                        case AudioRoute.TYPE_EARPIECE:
                            createEarpieceRoute();
                            break;
                        case AudioRoute.TYPE_BLUETOOTH_SCO:
                            if (mIsScoManagedByAudio) {
                                mCallAudioManager.getBluetoothStateReceiver()
                                        .handleActiveDeviceChanged(
                                                 audioRouteType, deviceInfo.getAddress());
                            }
                            break;
                        default:
                            break;
                    }
                } else {
                    AudioRoute route = mTypeRoutes.remove(audioRouteType);
                    updateAvailableRoutes(route, false);
                    if (audioRouteType == AudioRoute.TYPE_BLUETOOTH_SCO && mIsScoManagedByAudio) {
                        mCallAudioManager.getBluetoothStateReceiver().handleActiveDeviceChanged(
                                audioRouteType, null);
                    }
                }
            }
        }
    }

    private final BroadcastReceiver mMuteChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.startSession("CARC.mCR");
            try {
                if (AudioManager.ACTION_MICROPHONE_MUTE_CHANGED.equals(intent.getAction())) {
                    if (mCallsManager.isInEmergencyCall()) {
                        Log.i(this, "Mute was externally changed when there's an emergency call. "
                                + "Forcing mute back off.");
                        sendMessageWithSessionInfo(MUTE_OFF);
                    } else {
                        sendMessageWithSessionInfo(MUTE_EXTERNALLY_CHANGED);
                    }
                } else if (AudioManager.STREAM_MUTE_CHANGED_ACTION.equals(intent.getAction())) {
                    int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                    boolean isStreamMuted = intent.getBooleanExtra(
                            AudioManager.EXTRA_STREAM_VOLUME_MUTED, false);

                    if (streamType == AudioManager.STREAM_RING && !isStreamMuted
                            && mCallAudioManager != null) {
                        Log.i(this, "Ring stream was un-muted.");
                        mCallAudioManager.onRingerModeChange();
                    }
                } else {
                    Log.w(this, "Received non-mute-change intent");
                }
            } finally {
                Log.endSession();
            }
        }
    };
    private CallAudioState mCallAudioState;
    private boolean mIsMute;
    private boolean mIsPending;
    private boolean mIsActive;
    private boolean mWasOnSpeaker;
    private AudioRoutesCallback mAudioRoutesCallback;
    private final TelecomMetricsController mMetricsController;

    public CallAudioRouteController(
            Context context, CallsManager callsManager,
            CallAudioManager.AudioServiceFactory audioServiceFactory,
            AudioRoute.Factory audioRouteFactory, WiredHeadsetManager wiredHeadsetManager,
            BluetoothRouteManager bluetoothRouteManager, StatusBarNotifier statusBarNotifier,
            FeatureFlags featureFlags, TelecomMetricsController metricsController,
            AnomalyReporterAdapter anomalyReporterAdapter) {
        mContext = context;
        mCallsManager = callsManager;
        mAudioManager = context.getSystemService(AudioManager.class);
        mAudioServiceFactory = audioServiceFactory;
        mAudioRouteFactory = audioRouteFactory;
        mWiredHeadsetManager = wiredHeadsetManager;
        mIsMute = false;
        mBluetoothRouteManager = bluetoothRouteManager;
        mStatusBarNotifier = statusBarNotifier;
        mFeatureFlags = featureFlags;
        mMetricsController = metricsController;
        mAnomalyReporterAdapter = anomalyReporterAdapter;
        mFocusType = NO_FOCUS;
        mScoAudioConnectedDevice = null;
        mUsePreferredDeviceStrategy = true;
        mWasOnSpeaker = false;
        setCurrentCommunicationDevice(null);
        mPreferredDeviceRoute = DUMMY_ROUTE;

        mTelecomLock = callsManager.getLock();
        HandlerThread handlerThread = new HandlerThread(this.getClass().getSimpleName());
        handlerThread.start();

        IntentFilter micMuteChangedFilter = new IntentFilter(
                AudioManager.ACTION_MICROPHONE_MUTE_CHANGED);
        micMuteChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mMuteChangeReceiver, micMuteChangedFilter);

        IntentFilter muteChangedFilter = new IntentFilter(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        muteChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mMuteChangeReceiver, muteChangedFilter);

        // Register AudioManager#onCommunicationDeviceChangedListener listener to receive updates
        // to communication device (via AudioManager#setCommunicationDevice). This is a replacement
        // to using broadcasts in the hopes of improving performance.
        mAudioManagerListenerExecutor = Executors.newSingleThreadExecutor();
        mCommunicationDeviceListener = new AudioManager.OnCommunicationDeviceChangedListener() {
            @Override
            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                try {
                    Log.startSession("CARC.oCDC");
                    @AudioRoute.AudioRouteType int audioType = getAudioType(device);
                    // Get the previous communication device for handling SCO disconnected for HFP
                    // devices
                    AudioDeviceInfo previousDevice = getCurrentCommunicationDevice();
                    setCurrentCommunicationDevice(device);
                    Log.i(this, "onCommunicationDeviceChanged: previous device (%s), current "
                            + "device (%s), audioType (%d)", previousDevice, device, audioType);
                    // Todo: Update to account for all device types once we support audio route
                    //  centralization.
                    if (mFeatureFlags.ignoreBtBroadcast()) {
                        handleCommunicationDeviceChanged(audioType, device, previousDevice);
                    } else {
                        handleCommunicationDeviceChangedOld(audioType, device);
                    }
                } finally {
                    Log.endSession();
                }
            }
        };

        mIsScoManagedByAudio = android.media.audio.Flags.scoManagedByAudio()
                && BluetoothProperties.isScoManagedByAudioEnabled().orElse(false);

        // Register the  AudioManager. OnPreferredDevicesForStrategyChangedListener listener to
        // receive updates for the communication device. This is a replacement to directly querying
        // the preferred device via AudioManager#getPreferredDeviceForStrategy, which was known
        // to hold up the invoking thread.
        mPreferredDeviceListener = (strategy, devices) -> {
            try {
                Log.startSession("CARC.oPDFSCL");
                final AudioAttributes attr = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build();
                if (!devices.isEmpty() && strategy.supportsAudioAttributes(attr)) {
                    AudioRoute audioRoute = getPreferredDeviceAudioRoute(devices.getFirst());
                    Log.i(this, "OnPreferredDevicesForStrategyChangedListener: preferred device "
                            + "was updated to %s", audioRoute);
                    // Get the first device listed
                    setPreferredDeviceRoute(audioRoute);
                }
            } finally {
                Log.endSession();
            }
        };

        // Create handler
        mHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                synchronized (this) {
                    preHandleMessage(msg);
                    String address;
                    BluetoothDevice bluetoothDevice;
                    int focus;
                    int handleEndTone;
                    @AudioRoute.AudioRouteType int type;
                    switch (msg.what) {
                        case CONNECT_WIRED_HEADSET:
                            handleWiredHeadsetConnected();
                            break;
                        case DISCONNECT_WIRED_HEADSET:
                            handleWiredHeadsetDisconnected();
                            break;
                        case CONNECT_DOCK:
                            handleDockConnected();
                            break;
                        case DISCONNECT_DOCK:
                            handleDockDisconnected();
                            break;
                        case BLUETOOTH_DEVICE_LIST_CHANGED:
                            break;
                        case BT_ACTIVE_DEVICE_PRESENT:
                            type = msg.arg1;
                            address = (String) ((SomeArgs) msg.obj).arg2;
                            handleBtActiveDevicePresent(type, address);
                            break;
                        case BT_ACTIVE_DEVICE_GONE:
                            type = msg.arg1;
                            handleBtActiveDeviceGone(type);
                            break;
                        case BT_DEVICE_ADDED:
                            type = msg.arg1;
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtConnected(type, bluetoothDevice);
                            break;
                        case BT_DEVICE_REMOVED:
                            type = msg.arg1;
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtDisconnected(type, bluetoothDevice);
                            break;
                        case SWITCH_EARPIECE:
                        case USER_SWITCH_EARPIECE:
                            handleSwitchEarpiece(msg.what == USER_SWITCH_EARPIECE);
                            break;
                        case SWITCH_BLUETOOTH:
                        case USER_SWITCH_BLUETOOTH:
                            address = (String) ((SomeArgs) msg.obj).arg2;
                            handleSwitchBluetooth(address, msg.what == USER_SWITCH_BLUETOOTH);
                            break;
                        case SWITCH_HEADSET:
                        case USER_SWITCH_HEADSET:
                            handleSwitchHeadset(msg.what == USER_SWITCH_HEADSET);
                            break;
                        case SWITCH_SPEAKER:
                        case USER_SWITCH_SPEAKER:
                            handleSwitchSpeaker();
                            break;
                        case SWITCH_BASELINE_ROUTE:
                            address = (String) ((SomeArgs) msg.obj).arg2;
                            handleSwitchBaselineRoute(false,
                                    msg.arg1 == INCLUDE_BLUETOOTH_IN_BASELINE, address);
                            break;
                        case USER_SWITCH_BASELINE_ROUTE:
                            handleSwitchBaselineRoute(true,
                                    msg.arg1 == INCLUDE_BLUETOOTH_IN_BASELINE, null);
                            break;
                        case SPEAKER_ON:
                            handleSpeakerOn();
                            break;
                        case SPEAKER_OFF:
                            handleSpeakerOff();
                            break;
                        case STREAMING_FORCE_ENABLED:
                            handleStreamingEnabled();
                            break;
                        case STREAMING_FORCE_DISABLED:
                            handleStreamingDisabled();
                            break;
                        case BT_AUDIO_CONNECTED:
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtAudioActive(bluetoothDevice);
                            break;
                        case BT_AUDIO_DISCONNECTED:
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtAudioInactive(bluetoothDevice);
                            break;
                        case MUTE_ON:
                            handleMuteChanged(true);
                            break;
                        case MUTE_OFF:
                            handleMuteChanged(false);
                            break;
                        case MUTE_EXTERNALLY_CHANGED:
                            handleMuteChanged(mAudioManager.isMicrophoneMute());
                            break;
                        case TOGGLE_MUTE:
                            handleMuteChanged(!mIsMute);
                            break;
                        case SWITCH_FOCUS:
                            focus = msg.arg1;
                            handleEndTone = (int) ((SomeArgs) msg.obj).arg2;
                            handleSwitchFocus(focus, handleEndTone);
                            break;
                        case EXIT_PENDING_ROUTE:
                            handleExitPendingRoute();
                            break;
                        case UPDATE_SYSTEM_AUDIO_ROUTE:
                            // Based on the available routes for foreground call, adjust routing.
                            updateRouteForForeground();
                            // Force update to notify all ICS/CS.
                            updateCallAudioState(new CallAudioState(mIsMute,
                                    mCallAudioState.getRoute(),
                                    mCallAudioState.getSupportedRouteMask(),
                                    mCallAudioState.getActiveBluetoothDevice(),
                                    mCallAudioState.getSupportedBluetoothDevices()));
                        default:
                            break;
                    }
                    postHandleMessage(msg);
                }
            }
        };
    }
    @Override
    public void initialize() {
        mAvailableRoutes = new HashSet<>();
        mCallSupportedRoutes = new HashSet<>();
        mBluetoothRoutes = Collections.synchronizedMap(new LinkedHashMap<>());
        mActiveDeviceCache = new HashMap<>();
        mActiveDeviceCache.put(AudioRoute.TYPE_BLUETOOTH_SCO, null);
        mActiveDeviceCache.put(AudioRoute.TYPE_BLUETOOTH_HA, null);
        mActiveDeviceCache.put(AudioRoute.TYPE_BLUETOOTH_LE, null);
        mActiveBluetoothDevice = null;
        mTypeRoutes = new ArrayMap<>();
        mStreamingRoutes = new HashSet<>();
        mPendingAudioRoute = new PendingAudioRoute(this, mAudioManager, mBluetoothRouteManager,
                mFeatureFlags);
        mStreamingRoute = new AudioRoute(AudioRoute.TYPE_STREAMING, null, null);
        mStreamingRoutes.add(mStreamingRoute);

        int supportMask = calculateSupportedRouteMaskInit();
        if ((supportMask & CallAudioState.ROUTE_SPEAKER) != 0) {
            int audioRouteType = AudioRoute.TYPE_SPEAKER;
            createSpeakerRoute();
        }

        if ((supportMask & CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            // Create wired headset routes
            mEarpieceWiredRoute = mAudioRouteFactory.create(AudioRoute.TYPE_WIRED, null,
                    mAudioManager);
            if (mEarpieceWiredRoute == null) {
                Log.w(this, "Can't find available audio device info for route TYPE_WIRED_HEADSET");
            } else {
                mTypeRoutes.put(AudioRoute.TYPE_WIRED, mEarpieceWiredRoute);
                updateAvailableRoutes(mEarpieceWiredRoute, true);
            }
        } else if ((supportMask & CallAudioState.ROUTE_EARPIECE) != 0) {
            createEarpieceRoute();
        }

        // set current route
        if (mEarpieceWiredRoute != null) {
            mCurrentRoute = mEarpieceWiredRoute;
        } else if (mSpeakerDockRoute != null) {
            mCurrentRoute = mSpeakerDockRoute;
        } else {
            mCurrentRoute = DUMMY_ROUTE;
        }
        // Audio ops will only ever be completed if there's a call placed and it gains
        // ACTIVE/RINGING focus, hence why the initial value is 0.
        mAudioOperationsCompleteLatch = new CountDownLatch(0);
        // This latch will be count down when ACTIVE/RINGING focus is gained. This is determined
        // when the routing goes active.
        mAudioActiveCompleteLatch = new CountDownLatch(1);
        mIsActive = false;
        mCallAudioState = new CallAudioState(mIsMute, ROUTE_MAP.get(mCurrentRoute.getType()),
                supportMask, null, new HashSet<>());
        mAudioManager.addOnCommunicationDeviceChangedListener(
                mAudioManagerListenerExecutor, mCommunicationDeviceListener);
        mAudioManager.addOnPreferredDevicesForStrategyChangedListener(mAudioManagerListenerExecutor,
                mPreferredDeviceListener);
        mAudioRoutesCallback = new AudioRoutesCallback();
        mAudioManager.registerAudioDeviceCallback(mAudioRoutesCallback, mHandler);
    }

    @Override
    public void sendMessageWithSessionInfo(int message) {
        sendMessageWithSessionInfo(message, 0, (String) null);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg) {
        sendMessageWithSessionInfo(message, arg, (String) null);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, String data) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = data;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, int data) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = data;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessageWithSessionInfoAtFront(int message, int arg, int data) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = data;
        mHandler.sendMessageAtFrontOfQueue(Message.obtain(mHandler, message, arg, 0, args));
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, BluetoothDevice bluetoothDevice) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = bluetoothDevice;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessage(int message, Runnable r) {
        r.run();
    }

    private void sendMessage(int what, int arg1, int arg2, Object obj) {
        mHandler.sendMessage(Message.obtain(mHandler, what, arg1, arg2, obj));
    }

    @Override
    public void setCallAudioManager(CallAudioManager callAudioManager) {
        mCallAudioManager = callAudioManager;
    }

    @Override
    public CallAudioState getCurrentCallAudioState() {
        return mCallAudioState;
    }

    @Override
    public boolean isHfpDeviceAvailable() {
        return !mBluetoothRoutes.isEmpty();
    }

    @Override
    public Handler getAdapterHandler() {
        return mHandler;
    }

    @Override
    public PendingAudioRoute getPendingAudioRoute() {
        return mPendingAudioRoute;
    }

    @Override
    public void dump(IndentingPrintWriter pw) {
    }

    private void preHandleMessage(Message msg) {
        if (msg.obj instanceof SomeArgs) {
            Session session = (Session) ((SomeArgs) msg.obj).arg1;
            String messageCodeName = MESSAGE_CODE_TO_NAME.get(msg.what, "unknown");
            Log.continueSession(session, "CARC.pM_" + messageCodeName);
            Log.i(this, "Message received: %s=%d, arg1=%d", messageCodeName, msg.what, msg.arg1);
        }
    }

    private void postHandleMessage(Message msg) {
        Log.endSession();
        if (msg.obj instanceof SomeArgs) {
            ((SomeArgs) msg.obj).recycle();
        }
    }

    public boolean isActive() {
        return mIsActive;
    }

    public boolean isPending() {
        return mIsPending;
    }

    private void routeTo(boolean isDestRouteActive, AudioRoute destRoute) {
        if (destRoute == null || (!destRoute.equals(mStreamingRoute)
                && !getCallSupportedRoutes().contains(destRoute))) {
            Log.i(this, "Ignore routing to unavailable route: %s", destRoute);
            if (mFeatureFlags.telecomMetricsSupport()) {
                mMetricsController.getErrorStats().log(ErrorStats.SUB_CALL_AUDIO,
                        ErrorStats.ERROR_AUDIO_ROUTE_UNAVAILABLE);
            }
            return;
        }
        // If another BT device connects during RINGING_FOCUS, in-band ringing will be disabled by
        // default. In this case, we should adjust the active routing value so that we don't try
        // to connect to the BT device as it will fail.
        isDestRouteActive = maybeAdjustActiveRouting(destRoute, isDestRouteActive);
        // Determine if the destination BT device's SCO is already connected.
        boolean isScoDeviceAlreadyConnected = mScoAudioConnectedDevice != null && isDestRouteActive
                && Objects.equals(mScoAudioConnectedDevice, mBluetoothRoutes.get(destRoute));

        // Determine the origin route for this audio switch.
        AudioRoute originRoute = mIsPending ? mPendingAudioRoute.getDestRoute() : mCurrentRoute;
        // Check if the origin BT device has already been disconnected by the stack,
        // which can happen due to race conditions during BT device switching.
        boolean isOriginAlreadyDisconnected = mLastScoDisconnectedDevice != null
                && Objects.equals(mLastScoDisconnectedDevice, mBluetoothRoutes.get(originRoute));
        // The last-disconnected state is transient and only relevant for this specific
        // routing operation. Clear it immediately after use to prevent stale state issues.
        if (mLastScoDisconnectedDevice != null) {
            mLastScoDisconnectedDevice = null;
        }
        // Decide whether to skip sending a SCO disconnect command.
        boolean shouldAvoidBtDisconnect = shouldAvoidBluetoothDisconnect(
                isScoDeviceAlreadyConnected, isOriginAlreadyDisconnected);
        // Override shouldAvoidBtDisconnect if we're moving to inactive routing. We will never run
        // fully through the destination routing logic but in the original routing logic, we should
        // ensure that we always call AudioManager#clearCommunicationDevice at the end of the call.
        // It's possible that isOriginAlreadyDisconnected is true by the time we process the
        // UNFOCUSED switch, in which case, we would end up not clearing the communication device.
        if (mIsActive && !isDestRouteActive) {
            shouldAvoidBtDisconnect = false;
        }

        // If the destination route is already the currently reported communication device from the
        // audio fwk, then we should only reflect the route change in the UI and not try to set/
        // clear the communication device. For routing centralization, we will improve this to only
        // update the UI when the requested route change matches up.
        boolean isDestRouteCommunicationDevice = mFeatureFlags
                .skipPendingMsgIfCommunicationDeviceSet() && isCurrentCommunicationDevice(
                        getCurrentCommunicationDevice(), destRoute);
        if (mIsPending) {
            if (destRoute.equals(mPendingAudioRoute.getDestRoute())
                    && (mIsActive == isDestRouteActive)) {
                return;
            }
            Log.i(this, "Override current pending route from %s(active=%b) to "
                            + "%s(active=%b). isOriginAlreadyDisconnected=[%b]",
                    mPendingAudioRoute.getDestRoute(), mIsActive, destRoute, isDestRouteActive,
                    isOriginAlreadyDisconnected);

            // Override the pending route destination.
            mPendingAudioRoute.setOrigRoute(mIsActive /* origin */,
                    mPendingAudioRoute.getDestRoute(), isDestRouteActive /* dest */,
                    shouldAvoidBtDisconnect, isDestRouteCommunicationDevice);
        } else {
            if (mCurrentRoute.equals(destRoute) && (mIsActive == isDestRouteActive)) {
                return;
            }
            Log.i(this, "Enter pending route, orig=%s(active=%b), dest=%s(active=%b)",
                    mCurrentRoute, mIsActive, destRoute, isDestRouteActive);

            // Set the original route for the pending state.
            if (getCallSupportedRoutes().contains(mCurrentRoute)) {
                mPendingAudioRoute.setOrigRoute(mIsActive /* origin */, mCurrentRoute,
                        isDestRouteActive /* dest */, shouldAvoidBtDisconnect,
                        isDestRouteCommunicationDevice);
            } else {
                // Avoid waiting for messages for an unavailable route.
                mPendingAudioRoute.setOrigRoute(mIsActive /* origin */, DUMMY_ROUTE,
                        isDestRouteActive /* dest */, shouldAvoidBtDisconnect,
                        isDestRouteCommunicationDevice);
            }
            mIsPending = true;
        }
        mPendingAudioRoute.setDestRoute(isDestRouteActive, destRoute,
                mBluetoothRoutes.get(destRoute), shouldAvoidBtDisconnect,
                isDestRouteCommunicationDevice);
        mIsActive = isDestRouteActive;
        mPendingAudioRoute.evaluatePendingState();
        if (mFeatureFlags.telecomMetricsSupport()) {
            mMetricsController.getAudioRouteStats().onRouteEnter(mPendingAudioRoute);
        }
    }

    private boolean shouldAvoidBluetoothDisconnect(boolean isScoDeviceAlreadyConnected,
                                                   boolean isOriginAlreadyDisconnected) {
        Log.i(this, "Evaluating BT disconnect: isScoConnected=%b, isOriginDisconnected=%b",
                isScoDeviceAlreadyConnected, isOriginAlreadyDisconnected);

        if (mFeatureFlags.avoidDiscOnBtToBtSwitch()) {
            // Avoids sending a disconnect command if the new device is already connected
            // or if the old device has already reported its disconnection.
            return isScoDeviceAlreadyConnected || isOriginAlreadyDisconnected;
        }
        return isScoDeviceAlreadyConnected;
    }

    private boolean isCurrentCommunicationDevice(AudioDeviceInfo currentCommunicationDevice,
            AudioRoute destRoute) {
        if (currentCommunicationDevice == null || destRoute == null) {
            return false;
        }
        if (destRoute.getBluetoothAddress() != null) {
            return Objects.equals(currentCommunicationDevice.getAddress(),
                    destRoute.getBluetoothAddress());
        } else {
            int audioRouteType = DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.getOrDefault(
                    currentCommunicationDevice.getType(), TYPE_INVALID);
            return destRoute.getType() == audioRouteType;
        }
    }

    private void handleWiredHeadsetConnected() {
        AudioRoute wiredHeadsetRoute = null;
        try {
            wiredHeadsetRoute = mAudioRouteFactory.create(AudioRoute.TYPE_WIRED, null,
                    mAudioManager);
        } catch (IllegalArgumentException e) {
            if (mFeatureFlags.telecomMetricsSupport()) {
                mMetricsController.getErrorStats().log(ErrorStats.SUB_CALL_AUDIO,
                        ErrorStats.ERROR_EXTERNAL_EXCEPTION);
            }
            Log.e(this, e, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(AudioRoute.TYPE_WIRED));
        }

        if (wiredHeadsetRoute != null) {
            updateAvailableRoutes(wiredHeadsetRoute, true);
            updateAvailableRoutes(mEarpieceWiredRoute, false);
            mTypeRoutes.put(AudioRoute.TYPE_WIRED, wiredHeadsetRoute);
            mEarpieceWiredRoute = wiredHeadsetRoute;
            routeTo(mIsActive, wiredHeadsetRoute);
            onAvailableRoutesChanged();
        }
    }

    public void handleWiredHeadsetDisconnected() {
        // Update audio route states
        AudioRoute wiredHeadsetRoute = mTypeRoutes.remove(AudioRoute.TYPE_WIRED);
        if (wiredHeadsetRoute != null) {
            updateAvailableRoutes(wiredHeadsetRoute, false);
            mEarpieceWiredRoute = null;
        }
        AudioRoute earpieceRoute = null;
        try {
            earpieceRoute = mTypeRoutes.get(AudioRoute.TYPE_EARPIECE) == null
                ? mAudioRouteFactory.create(AudioRoute.TYPE_EARPIECE, null,
                    mAudioManager)
                : mTypeRoutes.get(AudioRoute.TYPE_EARPIECE);
        } catch (IllegalArgumentException e) {
            if (mFeatureFlags.telecomMetricsSupport()) {
                mMetricsController.getErrorStats().log(ErrorStats.SUB_CALL_AUDIO,
                        ErrorStats.ERROR_EXTERNAL_EXCEPTION);
            }
            Log.e(this, e, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(AudioRoute.TYPE_EARPIECE));
        }
        if (earpieceRoute != null) {
            updateAvailableRoutes(earpieceRoute, true);
            mEarpieceWiredRoute = earpieceRoute;
            // In the case that the route was never created, ensure that we update the map.
            mTypeRoutes.putIfAbsent(AudioRoute.TYPE_EARPIECE, mEarpieceWiredRoute);
        }
        onAvailableRoutesChanged();

        // Route to expected state
        if (mCurrentRoute.equals(wiredHeadsetRoute)) {
            // Preserve speaker routing if it was the last audio routing path when the wired headset
            // disconnects. Ignore this special cased routing when the route isn't active
            // (in other words, when we're not in a call).
            AudioRoute route = mWasOnSpeaker && mIsActive && mSpeakerDockRoute != null
                    && mSpeakerDockRoute.getType() == AudioRoute.TYPE_SPEAKER
                    ? mSpeakerDockRoute : getBaseRoute(true, null);
            routeTo(mIsActive, route);
        }
    }

    private void handleDockConnected() {
        AudioRoute dockRoute = null;
        try {
            dockRoute = mAudioRouteFactory.create(AudioRoute.TYPE_DOCK, null, mAudioManager);
        } catch (IllegalArgumentException e) {
            if (mFeatureFlags.telecomMetricsSupport()) {
                mMetricsController.getErrorStats().log(ErrorStats.SUB_CALL_AUDIO,
                        ErrorStats.ERROR_EXTERNAL_EXCEPTION);
            }
            Log.e(this, e, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(AudioRoute.TYPE_WIRED));
        }

        if (dockRoute != null) {
            updateAvailableRoutes(dockRoute, true);
            updateAvailableRoutes(mSpeakerDockRoute, false);
            mTypeRoutes.put(AudioRoute.TYPE_DOCK, dockRoute);
            mSpeakerDockRoute = dockRoute;
            routeTo(mIsActive, dockRoute);
            onAvailableRoutesChanged();
        }
    }

    public void handleDockDisconnected() {
        // Update audio route states
        AudioRoute dockRoute = mTypeRoutes.get(AudioRoute.TYPE_DOCK);
        if (dockRoute != null) {
            updateAvailableRoutes(dockRoute, false);
            mSpeakerDockRoute = null;
        }
        AudioRoute speakerRoute = mTypeRoutes.get(AudioRoute.TYPE_SPEAKER);
        if (speakerRoute != null) {
            updateAvailableRoutes(speakerRoute, true);
            mSpeakerDockRoute = speakerRoute;
        }
        onAvailableRoutesChanged();

        // Route to baseline
        if (isDockRoute(dockRoute)) {
            routeTo(mIsActive, getBaseRoute(true, null));
        }
    }

    private boolean isDockRoute(AudioRoute dockRoute) {
        return mCurrentRoute.equals(dockRoute) || mCurrentRoute.equals(mSpeakerDockRoute);
    }

    private void handleStreamingEnabled() {
        if (!mCurrentRoute.equals(mStreamingRoute)) {
            routeTo(mIsActive, mStreamingRoute);
        } else {
            Log.i(this, "ignore enable streaming, already in streaming");
        }
    }

    private void handleStreamingDisabled() {
        if (mCurrentRoute.equals(mStreamingRoute)) {
            mCurrentRoute = DUMMY_ROUTE;
            onAvailableRoutesChanged();
            routeTo(mIsActive, getBaseRoute(true, null));
        } else {
            Log.i(this, "ignore disable streaming, not in streaming");
        }
    }

    /**
     * Handles the case when SCO audio is connected for the BT headset. This follows shortly after
     * the BT device has been established as an active device (BT_ACTIVE_DEVICE_PRESENT) and doesn't
     * apply to other BT device types. In this case, the pending audio route will process the
     * BT_AUDIO_CONNECTED message that will trigger routing to the pending destination audio route;
     * otherwise, routing will be ignored if there aren't pending routes to be processed.
     *
     * Message being handled: BT_AUDIO_CONNECTED
     */
    private void handleBtAudioActive(BluetoothDevice bluetoothDevice) {
        if (mIsPending && bluetoothDevice != null) {
            Log.i(this, "handleBtAudioActive: is pending path");
            if (mFeatureFlags.ignoreBtBroadcast()) {
                AudioRoute btRoute = getBluetoothRoute(TYPE_BLUETOOTH_SCO,
                        bluetoothDevice.getAddress());
                // If the connected HFP device isn't the current communication device, then don't
                // continue processing the message. We will wait until the audio fwk reports that
                // the communication device has been updated accordingly instead of relying on
                // what the BT broadcasts are telling us.
                if (!isCurrentCommunicationDevice(getCurrentCommunicationDevice(), btRoute)) {
                    Log.i(this, "handleBtAudioActive: %s is not the current"
                            + "communication device yet. Ignoring message.");
                    return;
                }
            }
            // Ensure we aren't keeping track of pending speaker off and SCO audio disconnected
            // messages  for this device if BT stack indicates that SCO audio is connected.
            mPendingAudioRoute.clearPendingMessage(
                    new Pair<>(BT_AUDIO_DISCONNECTED, bluetoothDevice.getAddress()));
            mPendingAudioRoute.clearPendingMessage(new Pair<>(SPEAKER_OFF, null));
            // Maybe turn off speaker from notification bar. This will be a no-op if the enabled
            // status is already off.
            mStatusBarNotifier.notifySpeakerphone(false);
            if (Objects.equals(mPendingAudioRoute.getDestRoute().getBluetoothAddress(),
                    bluetoothDevice.getAddress())) {
                mPendingAudioRoute.onMessageReceived(new Pair<>(BT_AUDIO_CONNECTED,
                        bluetoothDevice.getAddress()), null);
            }
        }
    }

    /**
     * Handles the case when SCO audio is disconnected for the BT headset. In this case, the pending
     * audio route will process the BT_AUDIO_DISCONNECTED message which will trigger routing to the
     * pending destination audio route; otherwise, routing will be ignored if there aren't any
     * pending routes to be processed.
     *
     * Message being handled: BT_AUDIO_DISCONNECTED
     */
    private void handleBtAudioInactive(BluetoothDevice bluetoothDevice) {
        if (mIsPending && bluetoothDevice != null) {
            Log.i(this, "handleBtAudioInactive: is pending path");
            if (mFeatureFlags.ignoreBtBroadcast()) {
                AudioRoute btRoute = getBluetoothRoute(TYPE_BLUETOOTH_SCO,
                        bluetoothDevice.getAddress());
                // If the disconnected HFP device isn't reflected in current communication device,
                // then don't continue processing the disconnect message. We will wait until the
                // audio fwk reports that the communication device has changed first instead of
                // relying on what the BT broadcasts are telling us.
                if (isCurrentCommunicationDevice(getCurrentCommunicationDevice(), btRoute)) {
                    Log.i(this, "handleBtAudioInactive: %s is still the current"
                            + "communication device. Ignoring message.");
                    return;
                }
            }
            // Ensure we aren't keeping track of pending s SCO audio connected messages for this
            // device if the BT stack has indicated that SCO audio has disconnected.
            mPendingAudioRoute.clearPendingMessage(
                    new Pair<>(BT_AUDIO_CONNECTED, bluetoothDevice.getAddress()));
            if (Objects.equals(mPendingAudioRoute.getOrigRoute().getBluetoothAddress(),
                    bluetoothDevice.getAddress())) {
                mPendingAudioRoute.onMessageReceived(new Pair<>(BT_AUDIO_DISCONNECTED,
                        bluetoothDevice.getAddress()), null);
            }
        }
    }

    /**
     * This particular routing occurs when the BT device is trying to establish itself as a
     * connected device (refer to BluetoothStateReceiver#handleConnectionStateChanged). The device
     * is included as an available route and cached into the current BT routes.
     *
     * Message being handled: BT_DEVICE_ADDED
     */
    private void handleBtConnected(@AudioRoute.AudioRouteType int type,
            BluetoothDevice bluetoothDevice) {
        if (containsHearingAidPair(type, bluetoothDevice)) {
            return;
        }

        AudioRoute bluetoothRoute = mAudioRouteFactory.create(type, bluetoothDevice.getAddress(),
                mAudioManager);
        if (bluetoothRoute == null) {
            Log.w(this, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(type));
        } else {
            Log.i(this, "bluetooth route added: " + bluetoothRoute);
            updateAvailableRoutes(bluetoothRoute, true);
            mBluetoothRoutes.put(bluetoothRoute, bluetoothDevice);
            onAvailableRoutesChanged();
        }
    }

    /**
     * Handles the case when the BT device is in a disconnecting/disconnected state. In this case,
     * the audio route for the specified device is removed from the available BT routes and the
     * audio is routed to an available route if the current route is pointing to the device which
     * got disconnected.
     *
     * Message being handled: BT_DEVICE_REMOVED
     */
    private void handleBtDisconnected(@AudioRoute.AudioRouteType int type,
            BluetoothDevice bluetoothDevice) {
        // Clean up unavailable routes
        AudioRoute bluetoothRoute = getBluetoothRoute(type, bluetoothDevice.getAddress());
        if (bluetoothRoute != null) {
            Log.i(this, "bluetooth route removed: " + bluetoothRoute);
            mBluetoothRoutes.remove(bluetoothRoute);
            updateAvailableRoutes(bluetoothRoute, false);
            onAvailableRoutesChanged();
        }

        // Fallback to an available route
        if (Objects.equals(mCurrentRoute, bluetoothRoute)) {
            routeTo(mIsActive, getBaseRoute(true, null));
        }
    }

    /**
     * This particular routing occurs when the specified bluetooth device is marked as the active
     * device (refer to BluetoothStateReceiver#handleActiveDeviceChanged). This takes care of
     * moving the call audio route to the bluetooth route.
     *
     * Message being handled: BT_ACTIVE_DEVICE_PRESENT
     */
    private void handleBtActiveDevicePresent(@AudioRoute.AudioRouteType int type,
            String deviceAddress) {
        AudioRoute bluetoothRoute = getBluetoothRoute(type, deviceAddress);
        boolean isBtDeviceCurrentActive = Objects.equals(bluetoothRoute,
                getArbitraryBluetoothDevice());
        if (bluetoothRoute != null && isBtDeviceCurrentActive) {
            Log.i(this, "request to route to bluetooth route: %s (active=%b)", bluetoothRoute,
                    mIsActive);
            routeTo(mIsActive, bluetoothRoute);
        } else {
            Log.i(this, "request to route to unavailable bluetooth route or the route isn't the "
                    + "currently active device - type (%s), address (%s)", type, deviceAddress);
        }
    }

    /**
     * Handles routing for when the active BT device is removed for a given audio route type. In
     * this case, the audio is routed to another available route if the current route hasn't been
     * adjusted yet or there is a pending destination route associated with the device type that
     * went inactive. Note that BT_DEVICE_REMOVED will be processed first in this case, which will
     * handle removing the BT route for the device that went inactive as well as falling back to
     * an available route.
     *
     * Message being handled: BT_ACTIVE_DEVICE_GONE
     */
    private void handleBtActiveDeviceGone(@AudioRoute.AudioRouteType int type) {
        // Determine what the active device for the BT audio type was so that we can exclude this
        // device from being used when calculating the base route.
        String previouslyActiveDeviceAddress = mActiveDeviceCache.get(type);
        // It's possible that the dest route hasn't been set yet when the controller is first
        // initialized.
        boolean pendingRouteNeedsUpdate = mPendingAudioRoute.getDestRoute() != null
                && mPendingAudioRoute.getDestRoute().getType() == type;
        boolean currentRouteNeedsUpdate = mCurrentRoute.getType() == type;
        if (pendingRouteNeedsUpdate) {
            pendingRouteNeedsUpdate = mPendingAudioRoute.getDestRoute().getBluetoothAddress()
                    .equals(previouslyActiveDeviceAddress);
        }
        if (currentRouteNeedsUpdate) {
            currentRouteNeedsUpdate = mCurrentRoute.getBluetoothAddress()
                    .equals(previouslyActiveDeviceAddress);
        }
        if ((mIsPending && pendingRouteNeedsUpdate) || (!mIsPending && currentRouteNeedsUpdate)) {
            maybeDisableWasOnSpeaker(true);
            // Fallback to an available route excluding the previously active device.
            routeTo(mIsActive, getBaseRoute(true, previouslyActiveDeviceAddress));
        }
    }

    private void handleMuteChanged(boolean mute) {
        mIsMute = mute;
        if (mIsMute != mAudioManager.isMicrophoneMute() && mIsActive) {
            IAudioService audioService = mAudioServiceFactory.getAudioService();
            Log.i(this, "changing microphone mute state to: %b [serviceIsNull=%b]", mute,
                    audioService == null);
            if (audioService != null) {
                try {
                    audioService.setMicrophoneMute(mute, mContext.getOpPackageName(),
                            mCallsManager.getCurrentUserHandle().getIdentifier(),
                            mContext.getAttributionTag());
                } catch (RemoteException e) {
                    if (mFeatureFlags.telecomMetricsSupport()) {
                        mMetricsController.getErrorStats().log(ErrorStats.SUB_CALL_AUDIO,
                                ErrorStats.ERROR_EXTERNAL_EXCEPTION);
                    }
                    Log.e(this, e, "Remote exception while toggling mute.");
                    return;
                }
            }
        }
        onMuteStateChanged(mIsMute);
    }

    private void handleSwitchFocus(int focus, int handleEndTone) {
        Log.i(this, "handleSwitchFocus: focus (%s)", focus);
        mFocusType = focus;
        switch (focus) {
            case NO_FOCUS -> {
                mWasOnSpeaker = false;
                // Notify the CallAudioModeStateMachine that audio operations are complete so
                // that we can relinquish audio focus.
                mCallAudioManager.notifyAudioOperationsComplete();
                // Reset mute state after call ends. This should remain unaffected if audio routing
                // never went active.
                handleMuteChanged(false);
                // Ensure we reset call audio state at the end of the call (i.e. if we're on
                // speaker, route back to earpiece). If we're on BT, remain on BT if it's still
                // connected.
                AudioRoute route = calculateBaselineRoute(false, true, null);
                routeTo(false, route);
                // Clear pending messages
                mPendingAudioRoute.clearPendingMessages();
                clearRingingBluetoothAddress();
                mUsePreferredDeviceStrategy = true;
            }
            case ACTIVE_FOCUS -> {
                if (mFeatureFlags.preserveCallAudioRouting()) {
                    // Route to the current route if routing was already active. This should
                    // preserve the audio routing state when a call is held/unheld. Otherwise, we
                    // should calculate the base routing.
                    boolean useRingingBluetoothDevice = mBluetoothAddressForRinging != null
                            && mBluetoothAddressForRinging.equals(
                            mCurrentRoute.getBluetoothAddress());
                    AudioRoute route = useRingingBluetoothDevice
                            ? mCurrentRoute
                            : getBaseRoute(true, null);
                    // Use the current route for handling ringing focus when the flag is enabled
                    // unless the preferred device route is set as indicated by the audio fwk. We
                    // don't want to override this selection if the user had set a default audio
                    // route for calls.
                    if (!isPreferredDeviceSet() ) {
                        route = getCurrentOrPendingRoute();
                    }
                    routeTo(true, route);
                } else {
                    // Route to active baseline route (we may need to change audio route in the case
                    // when a video call is put on hold). Ignore route changes if we're handling
                    // playing the end tone. Otherwise, it's possible that we'll override the route
                    // a client has previously requested.
                    if (handleEndTone == 0) {
                        // Cache BT device switch in the case that inband ringing is disabled and
                        // audio was routed to a watch. When active focus is received, this
                        // selection will be honored provided that the current route is associated.
                        Log.i(this,
                                "handleSwitchFocus (ACTIVE_FOCUS): mBluetoothAddressForRinging = "
                                        + "%s, mCurrentRoute = %s", mBluetoothAddressForRinging,
                                mCurrentRoute);
                        AudioRoute audioRoute = mBluetoothAddressForRinging != null
                                && mBluetoothAddressForRinging.equals(
                                mCurrentRoute.getBluetoothAddress())
                                ? mCurrentRoute
                                : getBaseRoute(true, null);
                        routeTo(true, audioRoute);
                    }
                }
                // Once we have processed active focus once during the call, we can ignore
                // using the preferred device strategy.
                mUsePreferredDeviceStrategy = false;
                clearRingingBluetoothAddress();
            }
            case RINGING_FOCUS -> {
                if (!mIsActive) {
                    AudioRoute route = getBaseRoute(true, null);
                    // Use the current route for handling ringing focus when the flag is enabled
                    // unless the preferred device route is set as indicated by the audio fwk. We
                    // don't want to override this selection if the user had set a default audio
                    // route for calls.
                    if (mFeatureFlags.preserveCallAudioRouting() && !isPreferredDeviceSet()) {
                        route = getCurrentOrPendingRoute();
                    }
                    BluetoothDevice device = mBluetoothRoutes.get(route);
                    // Check if in-band ringtone is enabled for the device; if it isn't, move to
                    // inactive route.
                    if (device != null && !mBluetoothRouteManager
                            .isInbandRingEnabled(route.getType(), device)) {
                        routeTo(false, route);
                    } else {
                        routeTo(true, route);
                    }
                } else {
                    // Route is already active.
                    BluetoothDevice device = mBluetoothRoutes.get(mCurrentRoute);
                    if (device != null && !mBluetoothRouteManager
                            .isInbandRingEnabled(mCurrentRoute.getType(), device)) {
                        routeTo(false, mCurrentRoute);
                    }
                }
            }
        }
    }

    public void handleSwitchEarpiece(boolean isUserRequest) {
        AudioRoute earpieceRoute = mTypeRoutes.get(AudioRoute.TYPE_EARPIECE);
        if (earpieceRoute != null && getCallSupportedRoutes().contains(earpieceRoute)) {
            maybeDisableWasOnSpeaker(isUserRequest);
            routeTo(mIsActive, earpieceRoute);
        } else {
            Log.i(this, "ignore switch earpiece request");
        }
    }

    private void handleSwitchBluetooth(String address, boolean isUserRequest) {
        Log.i(this, "handle switch to bluetooth with address %s", address);
        AudioRoute bluetoothRoute = null;
        BluetoothDevice bluetoothDevice = null;
        if (address == null) {
            bluetoothRoute = getArbitraryBluetoothDevice();
            bluetoothDevice = mBluetoothRoutes.get(bluetoothRoute);
        } else {
            for (AudioRoute route : getCallSupportedRoutes()) {
                if (Objects.equals(address, route.getBluetoothAddress())) {
                    bluetoothRoute = route;
                    bluetoothDevice = mBluetoothRoutes.get(route);
                    break;
                }
            }
        }

        if (bluetoothRoute != null && bluetoothDevice != null) {
            maybeDisableWasOnSpeaker(isUserRequest);
            if (mFocusType == RINGING_FOCUS) {
                routeTo(mBluetoothRouteManager
                                .isInbandRingEnabled(bluetoothRoute.getType(), bluetoothDevice)
                                && mIsActive, bluetoothRoute);
                mBluetoothAddressForRinging = bluetoothDevice.getAddress();
            } else {
                routeTo(mIsActive, bluetoothRoute);
            }
        } else {
            Log.i(this, "ignore switch bluetooth request to unavailable address");
        }
    }

    /**
     * Retrieve the active BT device, if available, otherwise return the most recently tracked
     * active device, or null if none are available.
     * @return {@link AudioRoute} of the BT device.
     */
    private AudioRoute getArbitraryBluetoothDevice() {
        synchronized (mLock) {
            if (mActiveBluetoothDevice != null) {
                return getBluetoothRoute(
                    mActiveBluetoothDevice.first, mActiveBluetoothDevice.second);
            } else if (!mBluetoothRoutes.isEmpty()) {
                return mBluetoothRoutes.keySet().stream().toList()
                    .get(mBluetoothRoutes.size() - 1);
            }
            return null;
        }
    }

    private void handleSwitchHeadset(boolean isUserRequest) {
        AudioRoute headsetRoute = mTypeRoutes.get(AudioRoute.TYPE_WIRED);
        if (headsetRoute != null && getCallSupportedRoutes().contains(headsetRoute)) {
            maybeDisableWasOnSpeaker(isUserRequest);
            routeTo(mIsActive, headsetRoute);
        } else {
            Log.i(this, "ignore switch headset request");
        }
    }

    private void handleSwitchSpeaker() {
        if (mSpeakerDockRoute != null && getCallSupportedRoutes().contains(mSpeakerDockRoute)
                && mSpeakerDockRoute.getType() == AudioRoute.TYPE_SPEAKER) {
            routeTo(mIsActive, mSpeakerDockRoute);
        } else {
            Log.i(this, "ignore switch speaker request");
        }
    }

    private void handleSwitchBaselineRoute(boolean isExplicitUserRequest, boolean includeBluetooth,
            String btAddressToExclude) {
        Log.i(this, "handleSwitchBaselineRoute: includeBluetooth: %b, "
                + "btAddressToExclude: %s", includeBluetooth, btAddressToExclude);
        AudioRoute pendingDestRoute = mPendingAudioRoute.getDestRoute();
        boolean areExcludedBtAndDestBtSame = btAddressToExclude != null
                && pendingDestRoute != null
                && Objects.equals(btAddressToExclude, pendingDestRoute.getBluetoothAddress());
        Pair<Integer, String> btDevicePendingMsg =
                new Pair<>(BT_AUDIO_CONNECTED, btAddressToExclude);

        // If SCO is once again connected or there's a pending message for BT_AUDIO_CONNECTED, then
        // we know that the device has reconnected or is in the middle of connecting. Ignore routing
        // out of this BT device.
        boolean isExcludedDeviceConnectingOrConnected = areExcludedBtAndDestBtSame
                && (Objects.equals(mBluetoothRoutes.get(pendingDestRoute), mScoAudioConnectedDevice)
                || mPendingAudioRoute.getPendingMessages().contains(btDevicePendingMsg));
        // Check if the pending audio route or current route is already different from the route
        // including the BT device that should be excluded from route selection.
        boolean isCurrentOrDestRouteDifferent = btAddressToExclude != null
                && ((mIsPending && !btAddressToExclude.equals(mPendingAudioRoute.getDestRoute()
                .getBluetoothAddress())) || (!mIsPending && !btAddressToExclude.equals(
                        mCurrentRoute.getBluetoothAddress())));
        if (isExcludedDeviceConnectingOrConnected) {
            Log.i(this, "BT device with address (%s) is currently connecting/connected. "
                    + "Ignoring route switch.", btAddressToExclude);
            return;
        } else if (isCurrentOrDestRouteDifferent) {
            Log.i(this, "Current or pending audio route isn't routed to device with address "
                    + "(%s). Ignoring route switch.", btAddressToExclude);
            return;
        }
        maybeDisableWasOnSpeaker(isExplicitUserRequest);
        routeTo(mIsActive, calculateBaselineRoute(isExplicitUserRequest, includeBluetooth,
                btAddressToExclude));
    }

    private void handleSpeakerOn() {
        if (isPending()) {
            Log.i(this, "handleSpeakerOn: sending SPEAKER_ON to pending audio route");
            // Clear any pending speaker off message as the speaker has been explicitly turned on as
            // indicated by the audio fwk.
            mPendingAudioRoute.clearPendingMessage(new Pair<>(SPEAKER_OFF, null));
            // Clear any pending BT_AUDIO_DISCONNECTED messages for connected BT devices if speaker
            // has explicitly been turned on.
            for (BluetoothDevice device: mBluetoothRoutes.values()) {
                mPendingAudioRoute.clearPendingMessage(new Pair<>(BT_AUDIO_DISCONNECTED,
                        device.getAddress()));
            }
            mPendingAudioRoute.onMessageReceived(new Pair<>(SPEAKER_ON, null), null);
            // Update status bar notification if we are in a call.
            mStatusBarNotifier.notifySpeakerphone(mCallsManager.hasAnyCalls());
        } else {
            if (mSpeakerDockRoute != null && getCallSupportedRoutes().contains(mSpeakerDockRoute)
                    && mSpeakerDockRoute.getType() == AudioRoute.TYPE_SPEAKER
                    && mCurrentRoute.getType() != AudioRoute.TYPE_SPEAKER) {
                // This path should not be hit. This means that some other application manipulated
                // the audio routing to turn speaker on. Telecom will always attempt to route to
                // speaker via USER_SWITCH_SPEAKER and change the routing, which would create a
                // pending route change. Generate an anomaly report if this happens.
                mAnomalyReporterAdapter.reportAnomaly(AUDIO_ROUTING_EXTERNAL_CHANGE_UUID,
                        AUDIO_ROUTING_EXTERNAL_CHANGE_MSG);
                routeTo(mIsActive, mSpeakerDockRoute);
                // Since the route switching triggered by this message, we need to manually send it
                // again so that we won't stuck in the pending route
                if (mIsActive) {
                    sendMessageWithSessionInfo(SPEAKER_ON);
                }
            }
        }
    }

    private void handleSpeakerOff() {
        if (isPending()) {
            Log.i(this, "handleSpeakerOff - sending SPEAKER_OFF to pending audio route");
            // Clear any pending speaker on message as the speaker has been explicitly turned off as
            // indicated by the audio fwk.
            mPendingAudioRoute.clearPendingMessage(new Pair<>(SPEAKER_ON, null));
            mPendingAudioRoute.onMessageReceived(new Pair<>(SPEAKER_OFF, null), null);
            // Update status bar notification
            mStatusBarNotifier.notifySpeakerphone(false);
        } else if (mCurrentRoute.getType() == AudioRoute.TYPE_SPEAKER) {
            // This path should not be hit. This means that some other application manipulated the
            // audio routing to turn speaker off. Telecom will always attempt to route out of
            // speaker via user switches (I.e. USER_SWITCH_EARPIECE) and change the routing, which
            // would create a pending route change. Generate an anomaly report if this happens.
            mAnomalyReporterAdapter.reportAnomaly(AUDIO_ROUTING_EXTERNAL_CHANGE_UUID,
                    AUDIO_ROUTING_EXTERNAL_CHANGE_MSG);
            AudioRoute newRoute = getBaseRoute(true, null);
            // Route to whatever the current communication device is as reported by the audio fwk.
            AudioRoute communicationDeviceRoute = getAudioRouteForAudioDeviceInfo(
                    getCurrentCommunicationDevice());
            if (mFeatureFlags.ignoreBtBroadcast() && isValidRoute(communicationDeviceRoute)) {
                newRoute = communicationDeviceRoute;
            }
            routeTo(mIsActive, newRoute);
            // Since the route switching triggered by this message, we need to manually send it
            // again so that we won't stuck in the pending route. Do not send the additional
            // SPEAKER_OFF msg if we find that audio wasn't routed out of speaker. This would have
            // the potential to cause an infinite loop if routing doesn't change.
            if (mIsActive && newRoute.getType() != TYPE_SPEAKER) {
                sendMessageWithSessionInfo(SPEAKER_OFF);
            }
            onAvailableRoutesChanged();
        }
    }

    /**
     * This is invoked when there are no more pending audio routes to be processed, which signals
     * a change for the current audio route and the call audio state to be updated accordingly.
     */
    public void handleExitPendingRoute() {
        if (mIsPending) {
            mCurrentRoute = mPendingAudioRoute.getDestRoute();
            Log.addEvent(mCallsManager.getForegroundCall(), LogUtils.Events.AUDIO_ROUTE,
                    "Entering audio route: " + mCurrentRoute + " (active=" + mIsActive + ")");
            mIsPending = false;
            mPendingAudioRoute.clearPendingMessages();
            onCurrentRouteChanged();
            if (mIsActive) {
                // Only set mWasOnSpeaker if the routing was active. We don't want to consider this
                // selection outside of a call.
                if (mCurrentRoute.getType() == TYPE_SPEAKER) {
                    mWasOnSpeaker = true;
                }
                // Reinitialize the audio ops complete latch since the routing went active. We
                // should always expect operations to complete after this point.
                if (mAudioOperationsCompleteLatch.getCount() == 0) {
                    mAudioOperationsCompleteLatch = new CountDownLatch(1);
                }
                mAudioActiveCompleteLatch.countDown();
            } else {
                // Reinitialize the active routing latch when audio ops are complete so that it can
                // once again be processed when a new call is placed/received.
                if (mAudioActiveCompleteLatch.getCount() == 0) {
                    mAudioActiveCompleteLatch = new CountDownLatch(1);
                }
                mAudioOperationsCompleteLatch.countDown();
            }
            if (mFeatureFlags.telecomMetricsSupport()) {
                mMetricsController.getAudioRouteStats().onRouteExit(mPendingAudioRoute, true);
            }
        }
    }

    private void onCurrentRouteChanged() {
        synchronized (mLock) {
            BluetoothDevice activeBluetoothDevice = null;
            int route = ROUTE_MAP.get(mCurrentRoute.getType());
            if (route == CallAudioState.ROUTE_STREAMING) {
                updateCallAudioState(new CallAudioState(mIsMute, route, route));
                return;
            }
            if (route == CallAudioState.ROUTE_BLUETOOTH) {
                activeBluetoothDevice = mBluetoothRoutes.get(mCurrentRoute);
            }
            updateCallAudioState(new CallAudioState(mIsMute, route,
                    mCallAudioState.getRawSupportedRouteMask(), activeBluetoothDevice,
                    mCallAudioState.getSupportedBluetoothDevices()));
        }
    }

    private void onAvailableRoutesChanged() {
        synchronized (mLock) {
            int routeMask = 0;
            Set<BluetoothDevice> availableBluetoothDevices = new HashSet<>();
            for (AudioRoute route : getCallSupportedRoutes()) {
                routeMask |= ROUTE_MAP.get(route.getType());
                if (BT_AUDIO_ROUTE_TYPES.contains(route.getType())) {
                    BluetoothDevice deviceToAdd = mBluetoothRoutes.get(route);
                    // Only include the lead device for LE audio (otherwise, the routes will show
                    // two separate devices in the UI).
                    if (deviceToAdd != null && route.getType() == AudioRoute.TYPE_BLUETOOTH_LE
                            && getLeAudioService() != null) {
                        int groupId = getLeAudioService().getGroupId(deviceToAdd);
                        if (groupId != BluetoothLeAudio.GROUP_ID_INVALID) {
                            deviceToAdd = getLeAudioService().getConnectedGroupLeadDevice(groupId);
                        }
                    }
                    // This will only ever be null when the lead device (LE) is disconnected and
                    // try to obtain the lead device for the 2nd bud.
                    if (deviceToAdd != null) {
                        availableBluetoothDevices.add(deviceToAdd);
                    }
                }
            }

            updateCallAudioState(new CallAudioState(mIsMute, mCallAudioState.getRoute(), routeMask,
                    mCallAudioState.getActiveBluetoothDevice(), availableBluetoothDevices));
        }
    }

    private void onMuteStateChanged(boolean mute) {
        updateCallAudioState(new CallAudioState(mute, mCallAudioState.getRoute(),
                mCallAudioState.getSupportedRouteMask(), mCallAudioState.getActiveBluetoothDevice(),
                mCallAudioState.getSupportedBluetoothDevices()));
    }

    /**
     * Retrieves the current call's supported audio route and adjusts the audio routing if the
     * current route isn't supported.
     */
    private void updateRouteForForeground() {
        boolean updatedRouteForCall = updateCallSupportedAudioRoutes();
        // Ensure that current call audio state has updated routes for current call.
        if (updatedRouteForCall) {
            mCallAudioState = new CallAudioState(mIsMute, mCallAudioState.getRoute(),
                    mCallSupportedRouteMask, mCallAudioState.getActiveBluetoothDevice(),
                    mCallAudioState.getSupportedBluetoothDevices());
            // Update audio route if foreground call doesn't support the current route.
            if ((mCallSupportedRouteMask & mCallAudioState.getRoute()) == 0) {
                routeTo(mIsActive, getBaseRoute(true, null));
            }
        }
    }

    /**
     * Update supported audio routes for the foreground call if present.
     */
    private boolean updateCallSupportedAudioRoutes() {
        int availableRouteMask = 0;
        Call foregroundCall = mCallsManager.getForegroundCall();
        mCallSupportedRoutes.clear();
        if (foregroundCall != null) {
            int foregroundCallSupportedRouteMask = foregroundCall.getSupportedAudioRoutes();
            for (AudioRoute route : getAvailableRoutes()) {
                int routeType = ROUTE_MAP.get(route.getType());
                availableRouteMask |= routeType;
                if ((routeType & foregroundCallSupportedRouteMask) == routeType) {
                    mCallSupportedRoutes.add(route);
                }
            }
            mCallSupportedRouteMask = availableRouteMask & foregroundCallSupportedRouteMask;
            return true;
        } else {
            mCallSupportedRouteMask = -1;
            return false;
        }
    }

    private void updateCallAudioState(CallAudioState newCallAudioState) {
        synchronized (mTelecomLock) {
            Log.i(this, "updateCallAudioState: updating call audio state to %s", newCallAudioState);
            CallAudioState oldState = mCallAudioState;
            mCallAudioState = newCallAudioState;
            // Update status bar notification
            mStatusBarNotifier.notifyMute(newCallAudioState.isMuted());
            mCallsManager.onCallAudioStateChanged(oldState, mCallAudioState);
            updateAudioStateForTrackedCalls(mCallAudioState);
        }
    }

    private void updateAudioStateForTrackedCalls(CallAudioState newCallAudioState) {
        List<Call> calls = new ArrayList<>(mCallsManager.getTrackedCalls());
        for (Call call : calls) {
            if (call != null && call.getConnectionService() != null) {
                call.getConnectionService().onCallAudioStateChanged(call, newCallAudioState);
            }
        }
    }

    private AudioRoute getPreferredDeviceAudioRoute(AudioDeviceAttributes deviceAttr) {
        Log.i(this, "getPreferredAudioRouteFromStrategy: preferred device is %s", deviceAttr);
        if (deviceAttr == null) {
            return DUMMY_ROUTE;
        }

        // Get corresponding audio route
        @AudioRoute.AudioRouteType int type = DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.get(
                deviceAttr.getType());
        AudioDeviceInfo currentCommunicationDevice = getCurrentCommunicationDevice();
        // We will default to TYPE_INVALID if the currentCommunicationDevice is null or the type
        // cannot be resolved from the given audio device info.
        int communicationDeviceAudioType = getAudioType(currentCommunicationDevice);
        // Sync the preferred device strategy with the current communication device if there's a
        // valid audio device output set as the preferred device strategy. This will address timing
        // issues between updates made to the preferred device strategy. From the audio fwk
        // standpoint, updates to the communication device take precedent to changes in the
        // preferred device strategy so the former should be used as the source of truth.
        if (type != TYPE_INVALID && communicationDeviceAudioType != TYPE_INVALID
                && communicationDeviceAudioType != type) {
            type = communicationDeviceAudioType;
        }
        if (BT_AUDIO_ROUTE_TYPES.contains(type)) {
            return getBluetoothRoute(type, deviceAttr.getAddress());
        } else {
            return mTypeRoutes.get(type);
        }
    }

    private AudioRoute getPreferredAudioRouteFromDefault(boolean isExplicitUserRequest,
            boolean includeBluetooth, String btAddressToExclude) {
        boolean skipEarpiece = false;
        Call foregroundCall = mCallAudioManager.getForegroundCall();
        if (!isExplicitUserRequest) {
            synchronized (mTelecomLock) {
                skipEarpiece = foregroundCall != null && foregroundCall.isActiveFocus()
                        && VideoProfile.isVideo(foregroundCall.getVideoState())
                        && !foregroundCall.isVideoCrbtForVoLteCall();
                Log.i(this, "skipEarpiece for video call?" + skipEarpiece);
            }
        }
        // Route to earpiece, wired, or speaker route if there are not bluetooth routes or if there
        // are only wearables available.
        AudioRoute activeWatchOrNonWatchDeviceRoute =
                getActiveWatchOrNonWatchDeviceRoute(btAddressToExclude);
        if ((!mCallSupportedRoutes.isEmpty() && (mCallSupportedRouteMask
                & CallAudioState.ROUTE_BLUETOOTH) == 0) || mBluetoothRoutes.isEmpty()
                || !includeBluetooth || activeWatchOrNonWatchDeviceRoute == null) {
            Log.i(this, "getPreferredAudioRouteFromDefault: Audio routing defaulting to "
                    + "available non-BT route.");
            boolean callSupportsEarpieceWiredRoute = mCallSupportedRoutes.isEmpty()
                    || mCallSupportedRoutes.contains(mEarpieceWiredRoute);
            // If call supported route doesn't contain earpiece/wired/BT, it should have speaker
            // enabled. Otherwise, no routes would be supported for the call which should never be
            // the case.
            AudioRoute defaultRoute = mEarpieceWiredRoute != null && callSupportsEarpieceWiredRoute
                    ? mEarpieceWiredRoute
                    : mSpeakerDockRoute;
            // Clean up the preserve speaker logic with the flag to preserve call audio routing. We
            // would be maintaining the same route when holding/unholding a call now when receiving
            // active focus. This should only be used for handling routing when a wired headset is
            // disconnected.
            boolean supportWasOnSpeakerLogic = mFeatureFlags.preserveCallAudioRouting() ?
                    false : mWasOnSpeaker;
            if ((skipEarpiece || supportWasOnSpeakerLogic) && defaultRoute != null
                    && defaultRoute.getType() == AudioRoute.TYPE_EARPIECE) {
                Log.i(this, "getPreferredAudioRouteFromDefault: Audio routing defaulting to "
                        + "speaker route for (video) call.");
                defaultRoute = mSpeakerDockRoute;
            }
            return defaultRoute;
        } else {
            // Most recent active route will always be the last in the array (ensure that we don't
            // auto route to a wearable device unless it's already active).
            String autoRoutingToWatchExcerpt = " (except inactive watch)";
            Log.i(this, "getPreferredAudioRouteFromDefault: Audio routing defaulting to "
                    + "most recently active BT route" + autoRoutingToWatchExcerpt + ".");
            return activeWatchOrNonWatchDeviceRoute;
        }
    }

    private int calculateSupportedRouteMaskInit() {
        Log.i(this, "calculateSupportedRouteMaskInit: is wired headset plugged in - %s",
                mWiredHeadsetManager.isPluggedIn());
        int routeMask = CallAudioState.ROUTE_SPEAKER;

        if (mWiredHeadsetManager.isPluggedIn()) {
            routeMask |= CallAudioState.ROUTE_WIRED_HEADSET;
        } else {
            AudioDeviceInfo[] deviceList = mAudioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS);
            // For debugging purposes in cases where the device list returned by the API fwk is
            // empty and we don't end up adding the earpiece route upon init.
            Log.i(this, "calculateSupportedRouteMaskInit: is device list size: %d",
                    deviceList.length);
            for (AudioDeviceInfo device: deviceList) {
                Log.i(this, "calculateSupportedRouteMaskInit: audio route type from audio "
                        + "device info: %d", device != null ? DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE
                        .getOrDefault(device.getType(), TYPE_INVALID) : TYPE_INVALID);
                if (device != null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    routeMask |= CallAudioState.ROUTE_EARPIECE;
                    break;
                }
            }
        }
        return routeMask;
    }

    @VisibleForTesting
    public Set<AudioRoute> getAvailableRoutes() {
        if (mCurrentRoute.equals(mStreamingRoute)) {
            return mStreamingRoutes;
        } else {
            return mAvailableRoutes;
        }
    }

    public Set<AudioRoute> getCallSupportedRoutes() {
        if (mCurrentRoute.equals(mStreamingRoute)) {
            return mStreamingRoutes;
        } else {
            if (mAvailableRoutesUpdated) {
                updateCallSupportedAudioRoutes();
                mAvailableRoutesUpdated = false;
            }
            return mCallSupportedRoutes.isEmpty() ? mAvailableRoutes : mCallSupportedRoutes;
        }
    }

    /* Only used for testing purposes */
    @VisibleForTesting
    public AudioRoute getCurrentRoute() {
        return mCurrentRoute;
    }

    /**
     * This should be used to determine what the most up to date route is. This is either the
     * current route or the pending destination route if there's a pending audio route
     * change.
     */
    public AudioRoute getCurrentOrPendingRoute() {
        return mIsPending ? mPendingAudioRoute.getDestRoute() : mCurrentRoute;
    }

    public AudioRoute getBluetoothRoute(@AudioRoute.AudioRouteType int audioRouteType,
            String address) {
        for (AudioRoute route : mBluetoothRoutes.keySet()) {
            if (route.getType() == audioRouteType && route.getBluetoothAddress().equals(address)) {
                return route;
            }
        }
        return null;
    }

    public AudioRoute getBaseRoute(boolean includeBluetooth, String btAddressToExclude) {
        // Catch-all case for all invocations to this method where we shouldn't be using
        // getPreferredAudioRouteFromStrategy
        if (!mUsePreferredDeviceStrategy) {
            return calculateBaselineRoute(false, includeBluetooth, btAddressToExclude);
        }
        // Get the preferred device value cached from the listener
        AudioRoute destRoute = getPreferredDeviceRoute();
        boolean isPreferredDeviceSet = isPreferredDeviceSet();
        if (!isPreferredDeviceSet) {
            Log.i(this, "getBaseRoute: preferred audio route is not reported by "
                    + "AudioManager; telecom to determine");
        } else {
            Log.i(this, "getBaseRoute: preferred audio route is %s", destRoute);
        }
        if (!isPreferredDeviceSet || (destRoute.getBluetoothAddress() != null && (!includeBluetooth
                || destRoute.getBluetoothAddress().equals(btAddressToExclude)))) {
            destRoute = getPreferredAudioRouteFromDefault(false, includeBluetooth, btAddressToExclude);
        }
        if (destRoute != null && !getCallSupportedRoutes().contains(destRoute)) {
            destRoute = null;
        }
        Log.i(this, "getBaseRoute - audio routing to %s", destRoute);
        return destRoute;
    }

    private AudioRoute calculateBaselineRoute(boolean isExplicitUserRequest,
            boolean includeBluetooth, String btAddressToExclude) {
        AudioRoute destRoute = getPreferredAudioRouteFromDefault(isExplicitUserRequest,
                includeBluetooth, btAddressToExclude);
        if (destRoute != null && !getCallSupportedRoutes().contains(destRoute)) {
            destRoute = null;
        }
        Log.i(this, "getBaseRoute - audio routing to %s", destRoute);
        return destRoute;
    }

    /**
     * Don't add additional AudioRoute when a hearing aid pair is detected. The devices have
     * separate addresses, so we need to perform explicit handling to ensure we don't
     * treat them as two separate devices.
     */
    private boolean containsHearingAidPair(@AudioRoute.AudioRouteType int type,
            BluetoothDevice bluetoothDevice) {
        // Check if it is a hearing aid pair and skip connecting to the other device in this case.
        // Traverse mBluetoothRoutes backwards as the most recently active device will be inserted
        // last.
        String existingHearingAidAddress = null;
        List<AudioRoute> bluetoothRoutes = mBluetoothRoutes.keySet().stream().toList();
        for (int i = bluetoothRoutes.size() - 1; i >= 0; i--) {
            AudioRoute audioRoute = bluetoothRoutes.get(i);
            if (audioRoute.getType() == AudioRoute.TYPE_BLUETOOTH_HA) {
                existingHearingAidAddress = audioRoute.getBluetoothAddress();
                break;
            }
        }

        // Check that route is for hearing aid and that there exists another hearing aid route
        // created for the first device (of the pair) that was connected.
        if (type == AudioRoute.TYPE_BLUETOOTH_HA && existingHearingAidAddress != null) {
            BluetoothAdapter bluetoothAdapter = mBluetoothRouteManager.getDeviceManager()
                    .getBluetoothAdapter();
            if (bluetoothAdapter != null) {
                List<BluetoothDevice> activeHearingAids =
                        bluetoothAdapter.getActiveDevices(BluetoothProfile.HEARING_AID);
                for (BluetoothDevice hearingAid : activeHearingAids) {
                    if (hearingAid != null && hearingAid.getAddress() != null) {
                        String address = hearingAid.getAddress();
                        if (address.equals(bluetoothDevice.getAddress())
                                || address.equals(existingHearingAidAddress)) {
                            Log.i(this, "containsHearingAidPair: Detected a hearing aid "
                                    + "pair, ignoring creating a new AudioRoute");
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Prevent auto routing to a wearable device when calculating the default bluetooth audio route
     * to move to. This function ensures that the most recently active non-wearable device is
     * selected for routing unless a wearable device has already been identified as an active
     * device.
     */
    private AudioRoute getActiveWatchOrNonWatchDeviceRoute(String btAddressToExclude) {
        List<AudioRoute> bluetoothRoutes = getAvailableBluetoothDevicesForRouting();
        // Traverse the routes from the most recently active recorded devices first.
        AudioRoute nonWatchDeviceRoute = null;
        for (int i = bluetoothRoutes.size() - 1; i >= 0; i--) {
            AudioRoute route = bluetoothRoutes.get(i);
            BluetoothDevice device = mBluetoothRoutes.get(route);
            // Skip excluded BT address and LE audio if it's not the lead device.
            if (route.getBluetoothAddress().equals(btAddressToExclude)
                    || isLeAudioNonLeadDeviceOrServiceUnavailable(route.getType(), device)) {
                continue;
            }
            // Check if the most recently active device is a watch device.
            boolean isActiveDevice;
            synchronized (mLock) {
                isActiveDevice = mActiveBluetoothDevice != null
                    && device.getAddress().equals(mActiveBluetoothDevice.second);
            }
            if (i == (bluetoothRoutes.size() - 1) && mBluetoothRouteManager.isWatch(device)
                    && (device.equals(mCallAudioState.getActiveBluetoothDevice())
                    || isActiveDevice)) {
                Log.i(this, "getActiveWatchOrNonWatchDeviceRoute: Routing to active watch - %s",
                        bluetoothRoutes.get(bluetoothRoutes.size() - 1));
                return bluetoothRoutes.get(bluetoothRoutes.size() - 1);
            }
            // Record the first occurrence of a non-watch device route if found.
            if (!mBluetoothRouteManager.isWatch(device)) {
                nonWatchDeviceRoute = route;
                break;
            }
        }

        Log.i(this, "Routing to a non-watch device - %s", nonWatchDeviceRoute);
        return nonWatchDeviceRoute;
    }

    private List<AudioRoute> getAvailableBluetoothDevicesForRouting() {
        List<AudioRoute> bluetoothRoutes = new ArrayList<>(mBluetoothRoutes.keySet());
        // Consider the active device (BT_ACTIVE_DEVICE_PRESENT) if it exists first.
        AudioRoute activeDeviceRoute = getArbitraryBluetoothDevice();
        if (activeDeviceRoute != null && (bluetoothRoutes.isEmpty()
                || !bluetoothRoutes.get(bluetoothRoutes.size() - 1).equals(activeDeviceRoute))) {
            Log.i(this, "getActiveWatchOrNonWatchDeviceRoute: active BT device (%s) present."
                    + "Considering this device for selection first.", activeDeviceRoute);
            bluetoothRoutes.add(activeDeviceRoute);
        }
        return bluetoothRoutes;
    }

    private boolean isLeAudioNonLeadDeviceOrServiceUnavailable(@AudioRoute.AudioRouteType int type,
            BluetoothDevice device) {
        BluetoothLeAudio leAudioService = getLeAudioService();
        if (type != AudioRoute.TYPE_BLUETOOTH_LE) {
            return false;
        } else if (leAudioService == null) {
            return true;
        }

        int groupId = leAudioService.getGroupId(device);
        if (groupId != BluetoothLeAudio.GROUP_ID_INVALID) {
            BluetoothDevice leadDevice = leAudioService.getConnectedGroupLeadDevice(groupId);
            Log.i(this, "Lead device for device (%s) is %s.", device, leadDevice);
            return leadDevice == null || !device.getAddress().equals(leadDevice.getAddress());
        }
        return false;
    }

    private BluetoothLeAudio getLeAudioService() {
        return mBluetoothRouteManager.getDeviceManager().getLeAudioService();
    }

    @VisibleForTesting
    public void setAudioManager(AudioManager audioManager) {
        mAudioManager = audioManager;
    }

    @VisibleForTesting
    public void setAudioRouteFactory(AudioRoute.Factory audioRouteFactory) {
        mAudioRouteFactory = audioRouteFactory;
    }

    public Map<AudioRoute, BluetoothDevice> getBluetoothRoutes() {
        return mBluetoothRoutes;
    }

    public void overrideIsPending(boolean isPending) {
        mIsPending = isPending;
    }

    @VisibleForTesting
    public void setScoAudioConnectedDevice(BluetoothDevice device) {
        mScoAudioConnectedDevice = device;
    }

    @VisibleForTesting
    public void setLastScoDisconnectedDevice(BluetoothDevice device) {
        mLastScoDisconnectedDevice = device;
    }

    private void clearRingingBluetoothAddress() {
        mBluetoothAddressForRinging = null;
    }

    /**
     * Update the active bluetooth device being tracked (as well as for individual profiles).
     * We need to keep track of active devices for individual profiles because of potential
     * inconsistencies found in BluetoothStateReceiver#handleActiveDeviceChanged. When multiple
     * profiles are paired, we could have a scenario where an active device A is replaced
     * with an active device B (from a different profile), which is then removed as an active
     * device shortly after, causing device A to be reactive. It's possible that the active device
     * changed intent is never received again for device A so an active device cache is necessary
     * to track these devices at a profile level.
     * @param device {@link Pair} containing the BT audio route type (i.e. SCO/HA/LE) and the
     *                           address of the device.
     */
    public void updateActiveBluetoothDevice(Pair<Integer, String> device) {
        synchronized (mLock) {
            mActiveDeviceCache.put(device.first, device.second);
            // Update most recently active device if address isn't null (meaning
            // some device is active).
            if (device.second != null) {
                mActiveBluetoothDevice = device;
            } else {
                // If a device was removed, check to ensure that no other device is
                //still considered active.
                boolean hasActiveDevice = false;
                List<Map.Entry<Integer, String>> activeBtDevices =
                        new ArrayList<>(mActiveDeviceCache.entrySet());
                for (Map.Entry<Integer, String> activeDevice : activeBtDevices) {
                    Integer btAudioType = activeDevice.getKey();
                    String address = activeDevice.getValue();
                    if (address != null) {
                        hasActiveDevice = true;
                        mActiveBluetoothDevice = new Pair<>(btAudioType, address);
                        break;
                    }
                }
                if (!hasActiveDevice) {
                    mActiveBluetoothDevice = null;
                }
            }
        }
    }

    private void updateAvailableRoutes(AudioRoute route, boolean includeRoute) {
        if (route == null) {
            return;
        }
        if (includeRoute) {
            mAvailableRoutes.add(route);
        } else {
            mAvailableRoutes.remove(route);
        }
        mAvailableRoutesUpdated = true;
    }

    @VisibleForTesting
    public void setActive(boolean active) {
        if (active) {
            mFocusType = ACTIVE_FOCUS;
        } else {
            mFocusType = NO_FOCUS;
        }
        mIsActive = active;
    }

    void fallBack(String btAddressToExclude) {
        mMetricsController.getAudioRouteStats().onRouteExit(mPendingAudioRoute, false);
        sendMessageWithSessionInfo(SWITCH_BASELINE_ROUTE, INCLUDE_BLUETOOTH_IN_BASELINE,
                btAddressToExclude);
    }

    public CountDownLatch getAudioOperationsCompleteLatch() {
        return mAudioOperationsCompleteLatch;
    }

    public CountDownLatch getAudioActiveCompleteLatch() {
        return mAudioActiveCompleteLatch;
    }

    private @AudioRoute.AudioRouteType int getAudioType(AudioDeviceInfo device) {
        return device != null
                ? DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.getOrDefault(
                device.getType(), TYPE_INVALID)
                : TYPE_INVALID;
    }

    @VisibleForTesting
    public boolean getUsePreferredDeviceStrategy() {
        return mUsePreferredDeviceStrategy;
    }

    @VisibleForTesting
    public void setCurrentCommunicationDevice(AudioDeviceInfo device) {
        synchronized (mLock) {
            mCurrentCommunicationDevice = device;
        }
    }

    public AudioDeviceInfo getCurrentCommunicationDevice() {
        synchronized (mLock) {
            return mCurrentCommunicationDevice;
        }
    }

    public void setPreferredDeviceRoute(AudioRoute route) {
        synchronized (mLock) {
            mPreferredDeviceRoute = route;
        }
    }

    public AudioRoute getPreferredDeviceRoute() {
        synchronized (mLock) {
            return mPreferredDeviceRoute;
        }
    }

    /**
     * Determines if there's a non-empty {@link AudioDeviceInfo} set by the audio fwk to signal that
     * the user has set a preferred audio route for placing/taking calls.
     *
     * Note: This is set via {@link AudioManager.OnPreferredDevicesForStrategyChangedListener} but
     * this API needs to be improved as we have seen instances where this is updated without any
     * user intervention as well.
     */
    private boolean isPreferredDeviceSet() {
        synchronized (mLock) {
            AudioRoute preferredDevice = getPreferredDeviceRoute();
            return preferredDevice != null && !preferredDevice.equals(DUMMY_ROUTE);
        }
    }

    private void maybeDisableWasOnSpeaker(boolean isUserRequest) {
        if (isUserRequest) {
            mWasOnSpeaker = false;
        }
    }

    /*
     * Adjusts routing to go inactive if we're active in the case that we're processing
     * RINGING_FOCUS and another BT headset is connected which causes in-band ringing to get
     * disabled. If we stay in active routing, Telecom will send requests to connect to these BT
     * devices while the call is ringing and each of these requests will fail at the BT stack side.
     * By default, in-band ringtone is disabled when more than one BT device is paired. Instead,
     * ringtone is played using the headset's default ringtone.
     */
    private boolean maybeAdjustActiveRouting(AudioRoute destRoute, boolean isDestRouteActive) {
        BluetoothDevice device = mBluetoothRoutes.get(destRoute);
        // If routing is active and in-band ringing is disabled while the call is ringing, move to
        // inactive routing.
        if (isDestRouteActive && mFocusType == RINGING_FOCUS && device != null
                && !mBluetoothRouteManager.isInbandRingEnabled(destRoute.getType(), device)) {
            return false;
        }
        else if (!isDestRouteActive && mFocusType == RINGING_FOCUS && (device == null
                || mBluetoothRouteManager.isInbandRingEnabled(destRoute.getType(), device))) {
            // If the routing is inactive while the call is ringing and we re-evaluate this to find
            // that we're routing to a non-BT device or a BT device that does support in-band
            // ringing, then re-enable active routing (i.e. second HFP headset is disconnected
            // while call is ringing).
            return true;
        }
        return isDestRouteActive;
    }

    private void createSpeakerRoute() {
        int audioRouteType = TYPE_SPEAKER;
        if (mSpeakerDockRoute == null) {
            //create type speaker
            mSpeakerDockRoute = mAudioRouteFactory.create(audioRouteType, null,
                    mAudioManager);
            // If speaker route couldn't be instantiated, try for TYPE_BUS
            if (mSpeakerDockRoute == null) {
                Log.i(this, "createSpeakerRoute: Can't find available audio device info for "
                        + "route TYPE_SPEAKER, trying for TYPE_BUS");
                mSpeakerDockRoute = mAudioRouteFactory.create(AudioRoute.TYPE_BUS, null,
                        mAudioManager);
                audioRouteType = AudioRoute.TYPE_BUS;
            }
            if (mSpeakerDockRoute == null) {
                Log.w(this, "createSpeakerRoute: Can't find available audio device info "
                        + "for route TYPE_SPEAKER or TYPE_BUS.");
            } else {
                // Update available routes
                mTypeRoutes.put(audioRouteType, mSpeakerDockRoute);
                updateAvailableRoutes(mSpeakerDockRoute, true);
            }
        } else {
            Log.i(this, "createSpeakerRoute: route already created. Skipping.");
        }
    }

    private void createEarpieceRoute() {
        // Create earpiece route
        if (mEarpieceWiredRoute != null) {
            Log.i(this, "createEarpieceRoute: route already created. Skipping.");
            return;
        }
        mEarpieceWiredRoute = mAudioRouteFactory.create(AudioRoute.TYPE_EARPIECE, null,
                mAudioManager);
        if (mEarpieceWiredRoute == null) {
            Log.w(this, "createEarpieceRoute: Can't find available audio device info for "
                    + "route TYPE_EARPIECE");
        } else {
            mTypeRoutes.put(AudioRoute.TYPE_EARPIECE, mEarpieceWiredRoute);
            updateAvailableRoutes(mEarpieceWiredRoute, true);
        }
    }

    @VisibleForTesting
    public AudioRoute getAudioRouteForTesting(int audioRouteType) {
        return switch (audioRouteType) {
            case AudioRoute.TYPE_EARPIECE, AudioRoute.TYPE_WIRED -> mEarpieceWiredRoute;
            case AudioRoute.TYPE_SPEAKER -> mSpeakerDockRoute;
            default -> DUMMY_ROUTE;
        };
    }

    @VisibleForTesting
    public AudioRoutesCallback getAudioRoutesCallback() {
        return mAudioRoutesCallback;
    }

    /**
     * Old logic for the Telecom processing done in response to the communication device updates
     * notified to us from the audio fwk. This logic only runs when
     * {@link FeatureFlags#ignoreBtBroadcast()} is disabled.
     */
    private void handleCommunicationDeviceChangedOld(int audioType, AudioDeviceInfo device) {
        if (audioType == TYPE_SPEAKER) {
            if (mCurrentRoute.getType() != TYPE_SPEAKER) {
                sendMessageWithSessionInfo(SPEAKER_ON);
            }
        } else if (mFeatureFlags.skipPendingMsgIfCommunicationDeviceSet()
                && audioType == TYPE_BLUETOOTH_SCO
                && mCurrentRoute.getType() != TYPE_BLUETOOTH_SCO) {
            // Handle switch to BT if communication device was updated. It's possible
            // that there are other communication device updates (i.e. to speaker) while
            // switching between HFP devices and Telecom may update the audio route to
            // speaker as a result.
            sendMessageWithSessionInfo(SWITCH_BLUETOOTH, 0, device.getAddress());
        } else {
            // Only send SPEAKER_OFF if the current route is on speaker
            if (mFeatureFlags.skipPendingMsgIfCommunicationDeviceSet()) {
                if (mCurrentRoute.getType() == TYPE_SPEAKER) {
                    sendMessageWithSessionInfo(SPEAKER_OFF);
                }
            } else {
                sendMessageWithSessionInfo(SPEAKER_OFF);
            }
        }
    }

    /**
     * Note that the old version of this method is dependent on the
     * {@link FeatureFlags#skipPendingMsgIfCommunicationDeviceSet()} flag. This new method assumes
     * that the latter flag will roll out much prior to the new flag
     * ({@link FeatureFlags#ignoreBtBroadcast()}) and avoids additional flag embedding logic that
     * would complicate this code block.
     *
     * This method processes the communication device change updates in two steps given that the
     * new communication device either doesn't correspond to the current audio route tracked in
     * Telecom or in the case of the same BT profiles that the addresses are different.
     * Telecom:
     *     1. We will first handle cleanup related to the current (or source) route. For speaker +
     *        SCO, this entails handling the pending messages for BT_AUDIO_DISCONNECTED or
     *        SPEAKER_OFF.
     *     2. We will then handle UI routing to what the new communication device is. We will first
     *        adjust the routing if needed. This may be needed in cases where audio fwk sends
     *        intermediate communication device updates that may alter the pending destination audio
     *        route (i.e. HFP A -> HFP B may result in an intermediary communication update to
     *        speaker). There is also no need for us to set the communication device again or
     *        disconnect SCO (for the legacy path done via BluetoothHeadset). This logic is already
     *        accounted for in #routeTo with "isDestRouteCommunicationDevice".
     * @param newAudioType The new audio route type for the new communication device reported by the
     *                     audio fwk.
     * @param newCommunicationDevice The new communication device update received from the audio fwk
     *                               signaling where audio is currently routed to.
     * @param previousCommunicationDevice The previous communication device stored in Telecom before
     *                                    the new communication device update was received from the
     *                                    audio fwk.
     */
    private void handleCommunicationDeviceChanged(int newAudioType,
            AudioDeviceInfo newCommunicationDevice,
            AudioDeviceInfo previousCommunicationDevice) {
        int currentAudioType = mCurrentRoute.getType();
        // We need to perform an update if the current communication device type is different from
        // whatever the current route. We should also account for multiple BT devices of the same
        // type.
        if (newAudioType != currentAudioType || (BT_AUDIO_ROUTE_TYPES.contains(newAudioType)
                && !Objects.equals(mCurrentRoute.getBluetoothAddress(),
                newCommunicationDevice.getAddress()))) {
            // SOURCE ROUTING HANDLING:
            // Handle clean-up for source route first before handling where audio should be
            // routed to. These are sent to handle any pending SPEAKER_OFF or BT_AUDIO_DISCONNECTED
            // messages.
            if (currentAudioType == TYPE_SPEAKER) {
                sendMessageWithSessionInfo(SPEAKER_OFF);
            } else if (previousCommunicationDevice != null && currentAudioType == TYPE_BLUETOOTH_SCO
                           && Objects.equals(mCurrentRoute.getBluetoothAddress(),
                               previousCommunicationDevice.getAddress())) {
                handleBtConnectionStateChanged(previousCommunicationDevice.getAddress(),
                        false /* isScoConnected */);
            }

            // DESTINATION ROUTING HANDLING:
            // Now we can handle the changes for where routing will go to after.
            if (newAudioType == TYPE_SPEAKER) {
                // Maybe handle switch to speaker first if needed (i.e. if there's an intermediary
                // switch from the audio fwk for the communication device) before it is updated to
                // speaker
                handleSwitchSpeaker();
                // Signal SPEAKER_ON to handle routing for the UI.
                sendMessageWithSessionInfo(SPEAKER_ON);
            } else if (newAudioType == TYPE_BLUETOOTH_SCO) {
                // Handle switch to BT in the case that the UI isn't already reflected
                handleSwitchBluetooth(newCommunicationDevice.getAddress(),
                        false /* isUserRequest */);
                // Signal BT_AUDIO_CONNECTED if needed
                handleBtConnectionStateChanged(newCommunicationDevice.getAddress(),
                        true /* isScoConnected */);
            }
        }
    }

    /**
     * This logic is duplicated from what's being handled in the
     * {@link com.android.server.telecom.bluetooth.BluetoothStateReceiver} class. Instead of
     * triggering the logic from the BT broadcast signals, we will do it via the communication
     * device updates provided by {@link AudioManager.OnCommunicationDeviceChangedListener}. We have
     * seen cases where the BT broadcast signals may not be aligned with what audio fwk reports.
     * Ultimately, those broadcasts are also relying on the audio fwk for signaling so we can avoid
     * the extra latency by listening to AudioManager directly.
     */
    private void handleBtConnectionStateChanged(String address, boolean isScoConnected) {
        AudioRoute btRoute = getBluetoothRoute(TYPE_BLUETOOTH_SCO, address);
        if (btRoute == null) {
            Log.w(this, "handleBtConnectionStateChanged: Audio route is undefined for "
                    + "address (%s)", address);
            return;
        }
        BluetoothDevice device = mBluetoothRoutes.get(btRoute);
        if (device == null) {
            Log.w(this, "handleBtConnectionStateChanged: Bluetooth device is undefined "
                    + "for the given route (%s)", btRoute);
            return;
        }
        Log.i(this, "handleBtConnectionStateChanged: SCO connected(%b) for address %s",
                isScoConnected, address);
        // BT_AUDIO_CONNECTED
        if (isScoConnected) {
            setScoAudioConnectedDevice(device);
            if (isPending() && Objects.equals(getPendingAudioRoute().getDestRoute(), btRoute)) {
                sendMessageWithSessionInfo(BT_AUDIO_CONNECTED, 0, device);
            } else {
                // It's possible that the initial BT connection fails but BT_AUDIO_CONNECTED
                // is sent later, indicating that SCO audio is on. We should route
                // appropriately in order for the UI to reflect this state.
                getPendingAudioRoute().overrideDestRoute(btRoute);
                overrideIsPending(true);
                getPendingAudioRoute().setCommunicationDeviceType(AudioRoute.TYPE_BLUETOOTH_SCO);
                sendMessageWithSessionInfo(EXIT_PENDING_ROUTE);
            }
        } else { // // BT_AUDIO_DISCONNECTED
            setLastScoDisconnectedDevice(device);
            setScoAudioConnectedDevice(null);
            if (isPending()) {
                sendMessageWithSessionInfo(BT_AUDIO_DISCONNECTED, 0, device);
            } else {
                // Handle case where BT stack signals SCO disconnected but Telecom isn't
                // processing any pending routes. This explicitly addresses cf instances
                // where a remote device disconnects SCO. Telecom should ensure that audio
                // is properly routed in the UI. Instead of calculating the baseline, we can just
                // route to whatever the audio fwk says the new communication device has changed to.
                int audioType = getAudioType(getCurrentCommunicationDevice());
                getPendingAudioRoute().setCommunicationDeviceType(audioType);
                routeTo(mIsActive, getAudioRouteForAudioDeviceInfo(
                        getCurrentCommunicationDevice()));
            }
        }
    }

    private AudioRoute getAudioRouteForAudioDeviceInfo(AudioDeviceInfo deviceInfo) {
        if (deviceInfo == null) {
            Log.w(this, "getAudioRouteForAudioDeviceInfo: device info is undefined");
            return DUMMY_ROUTE;
        }
        int audioType = getAudioType(deviceInfo);
        if (audioType == TYPE_INVALID) {
            Log.w(this, "getAudioRouteForAudioDeviceInfo: unable to resolve audio type for %s",
                    deviceInfo);
            return DUMMY_ROUTE;
        }
        if (BT_AUDIO_ROUTE_TYPES.contains(audioType)) {
            return getBluetoothRoute(audioType, deviceInfo.getAddress());
        } else {
            return mTypeRoutes.get(audioType);
        }
    }

    private boolean isValidRoute(AudioRoute route) {
        return route != DUMMY_ROUTE && route != null;
    }
}
