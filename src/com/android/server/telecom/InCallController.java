/*
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

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.telecom.CallAudioState;
import android.telecom.ConnectionService;
import android.telecom.DefaultDialerManager;
import android.telecom.InCallService;
import android.telecom.ParcelableCall;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
// TODO: Needed for move to system service: import com.android.internal.R;
import com.android.internal.telecom.IInCallService;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.SystemStateProvider.SystemStateListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds to {@link IInCallService} and provides the service to {@link CallsManager} through which it
 * can send updates to the in-call app. This class is created and owned by CallsManager and retains
 * a binding to the {@link IInCallService} (implemented by the in-call app).
 */
public final class InCallController extends CallsManagerListenerBase {
    /**
     * Used to bind to the in-call app and triggers the start of communication between
     * this class and in-call app.
     */
    private class InCallServiceConnection implements ServiceConnection {
        /** {@inheritDoc} */
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Log.startSession("ICSC.oSC");
            Log.d(this, "onServiceConnected: %s", name);
            onConnected(name, service);
            Log.endSession();
        }

        /** {@inheritDoc} */
        @Override public void onServiceDisconnected(ComponentName name) {
            Log.startSession("ICSC.oSD");
            Log.d(this, "onDisconnected: %s", name);
            onDisconnected(name);
            Log.endSession();
        }
    }

    private final Call.Listener mCallListener = new Call.ListenerBase() {
        @Override
        public void onConnectionCapabilitiesChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onConnectionPropertiesChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onCannedSmsResponsesLoaded(Call call) {
            updateCall(call);
        }

        @Override
        public void onVideoCallProviderChanged(Call call) {
            updateCall(call, true /* videoProviderChanged */);
        }

        @Override
        public void onStatusHintsChanged(Call call) {
            updateCall(call);
        }

        /**
         * Listens for changes to extras reported by a Telecom {@link Call}.
         *
         * Extras changes can originate from a {@link ConnectionService} or an {@link InCallService}
         * so we will only trigger an update of the call information if the source of the extras
         * change was a {@link ConnectionService}.
         *
         * @param call The call.
         * @param source The source of the extras change ({@link Call#SOURCE_CONNECTION_SERVICE} or
         *               {@link Call#SOURCE_INCALL_SERVICE}).
         * @param extras The extras.
         */
        @Override
        public void onExtrasChanged(Call call, int source, Bundle extras) {
            // Do not inform InCallServices of changes which originated there.
            if (source == Call.SOURCE_INCALL_SERVICE) {
                return;
            }
            updateCall(call);
        }

        /**
         * Listens for changes to extras reported by a Telecom {@link Call}.
         *
         * Extras changes can originate from a {@link ConnectionService} or an {@link InCallService}
         * so we will only trigger an update of the call information if the source of the extras
         * change was a {@link ConnectionService}.
         *  @param call The call.
         * @param source The source of the extras change ({@link Call#SOURCE_CONNECTION_SERVICE} or
         *               {@link Call#SOURCE_INCALL_SERVICE}).
         * @param keys The extra key removed
         */
        @Override
        public void onExtrasRemoved(Call call, int source, List<String> keys) {
            // Do not inform InCallServices of changes which originated there.
            if (source == Call.SOURCE_INCALL_SERVICE) {
                return;
            }
            updateCall(call);
        }

        @Override
        public void onHandleChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onCallerDisplayNameChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onVideoStateChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onTargetPhoneAccountChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onConferenceableCallsChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onConnectionEvent(Call call, String event, Bundle extras) {
            notifyConnectionEvent(call, event, extras);
        }
    };

    private final SystemStateListener mSystemStateListener = new SystemStateListener() {
        @Override
        public void onCarModeChanged(boolean isCarMode) {
            // Do something when the car mode changes.
        }
    };

    private static final int IN_CALL_SERVICE_TYPE_INVALID = 0;
    private static final int IN_CALL_SERVICE_TYPE_DIALER_UI = 1;
    private static final int IN_CALL_SERVICE_TYPE_SYSTEM_UI = 2;
    private static final int IN_CALL_SERVICE_TYPE_CAR_MODE_UI = 3;
    private static final int IN_CALL_SERVICE_TYPE_NON_UI = 4;

    /**
     * Maintains a binding connection to the in-call app(s).
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Map<ComponentName, InCallServiceConnection> mServiceConnections =
            new ConcurrentHashMap<ComponentName, InCallServiceConnection>(8, 0.9f, 1);

    /** The in-call app implementations, see {@link IInCallService}. */
    private final Map<ComponentName, IInCallService> mInCallServices = new ArrayMap<>();

    /**
     * The {@link ComponentName} of the bound In-Call UI Service.
     */
    private ComponentName mInCallUIComponentName;

    private final CallIdMapper mCallIdMapper = new CallIdMapper();

    /** The {@link ComponentName} of the default InCall UI. */
    private final ComponentName mSystemInCallComponentName;

    private final Context mContext;
    private final TelecomSystem.SyncRoot mLock;
    private final CallsManager mCallsManager;
    private final SystemStateProvider mSystemStateProvider;

    public InCallController(Context context, TelecomSystem.SyncRoot lock, CallsManager callsManager,
            SystemStateProvider systemStateProvider) {
        mContext = context;
        mLock = lock;
        mCallsManager = callsManager;
        mSystemStateProvider = systemStateProvider;

        Resources resources = mContext.getResources();
        mSystemInCallComponentName = new ComponentName(
                resources.getString(R.string.ui_default_package),
                resources.getString(R.string.incall_default_class));

        mSystemStateProvider.addListener(mSystemStateListener);
    }

    @Override
    public void onCallAdded(Call call) {
        if (!isBoundToServices()) {
            bindToServices(call);
        } else {
            adjustServiceBindingsForEmergency();

            Log.i(this, "onCallAdded: %s", call);
            // Track the call if we don't already know about it.
            addCall(call);

            for (Map.Entry<ComponentName, IInCallService> entry : mInCallServices.entrySet()) {
                ComponentName componentName = entry.getKey();
                IInCallService inCallService = entry.getValue();
                ParcelableCall parcelableCall = ParcelableCallUtils.toParcelableCall(call,
                        true /* includeVideoProvider */, mCallsManager.getPhoneAccountRegistrar());
                try {
                    inCallService.addCall(parcelableCall);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        Log.i(this, "onCallRemoved: %s", call);
        if (mCallsManager.getCalls().isEmpty()) {
            /** Let's add a 2 second delay before we send unbind to the services to hopefully
             *  give them enough time to process all the pending messages.
             */
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable("ICC.oCR") {
                @Override
                public void loggedRun() {
                    synchronized (mLock) {
                        // Check again to make sure there are no active calls.
                        if (mCallsManager.getCalls().isEmpty()) {
                            unbindFromServices();
                        }
                    }
                }
            }.prepare(), Timeouts.getCallRemoveUnbindInCallServicesDelay(
                            mContext.getContentResolver()));
        }
        call.removeListener(mCallListener);
        mCallIdMapper.removeCall(call);
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        updateCall(call);
    }

    @Override
    public void onConnectionServiceChanged(
            Call call,
            ConnectionServiceWrapper oldService,
            ConnectionServiceWrapper newService) {
        updateCall(call);
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState oldCallAudioState,
            CallAudioState newCallAudioState) {
        if (!mInCallServices.isEmpty()) {
            Log.i(this, "Calling onAudioStateChanged, audioState: %s -> %s", oldCallAudioState,
                    newCallAudioState);
            for (IInCallService inCallService : mInCallServices.values()) {
                try {
                    inCallService.onCallAudioStateChanged(newCallAudioState);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    @Override
    public void onCanAddCallChanged(boolean canAddCall) {
        if (!mInCallServices.isEmpty()) {
            Log.i(this, "onCanAddCallChanged : %b", canAddCall);
            for (IInCallService inCallService : mInCallServices.values()) {
                try {
                    inCallService.onCanAddCallChanged(canAddCall);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    void onPostDialWait(Call call, String remaining) {
        if (!mInCallServices.isEmpty()) {
            Log.i(this, "Calling onPostDialWait, remaining = %s", remaining);
            for (IInCallService inCallService : mInCallServices.values()) {
                try {
                    inCallService.setPostDialWait(mCallIdMapper.getCallId(call), remaining);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    @Override
    public void onIsConferencedChanged(Call call) {
        Log.d(this, "onIsConferencedChanged %s", call);
        updateCall(call);
    }

    void bringToForeground(boolean showDialpad) {
        if (!mInCallServices.isEmpty()) {
            for (IInCallService inCallService : mInCallServices.values()) {
                try {
                    inCallService.bringToForeground(showDialpad);
                } catch (RemoteException ignored) {
                }
            }
        } else {
            Log.w(this, "Asking to bring unbound in-call UI to foreground.");
        }
    }

    void silenceRinger() {
        if (!mInCallServices.isEmpty()) {
            for (IInCallService inCallService : mInCallServices.values()) {
                try {
                    inCallService.silenceRinger();
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    private void notifyConnectionEvent(Call call, String event, Bundle extras) {
        if (!mInCallServices.isEmpty()) {
            for (IInCallService inCallService : mInCallServices.values()) {
                try {
                    inCallService.onConnectionEvent(mCallIdMapper.getCallId(call), event, extras);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    /**
     * Unbinds an existing bound connection to the in-call app.
     */
    private void unbindFromServices() {
        Iterator<Map.Entry<ComponentName, InCallServiceConnection>> iterator =
            mServiceConnections.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<ComponentName, InCallServiceConnection> entry = iterator.next();
            Log.i(this, "Unbinding from InCallService %s", entry.getKey());
            try {
                mContext.unbindService(entry.getValue());
            } catch (Exception e) {
                Log.e(this, e, "Exception while unbinding from InCallService");
            }
            iterator.remove();
        }
        mInCallServices.clear();
    }

    /**
     * Binds to all the UI-providing InCallService as well as system-implemented non-UI
     * InCallServices. Method-invoker must check {@link #isBoundToServices()} before invoking.
     *
     * @param call The newly added call that triggered the binding to the in-call services.
     */
    @VisibleForTesting
    public void bindToServices(Call call) {
        ComponentName inCallUIService = null;
        ComponentName carModeInCallUIService = null;
        List<ComponentName> nonUIInCallServices = new LinkedList<>();

        // Loop through all the InCallService implementations that exist in the devices;
        PackageManager packageManager = mContext.getPackageManager();
        Intent serviceIntent = new Intent(InCallService.SERVICE_INTERFACE);
        for (ResolveInfo entry : packageManager.queryIntentServicesAsUser(
                serviceIntent,
                PackageManager.GET_META_DATA,
                mCallsManager.getCurrentUserHandle().getIdentifier())) {
            ServiceInfo serviceInfo = entry.serviceInfo;

            if (serviceInfo != null) {
                ComponentName componentName =
                        new ComponentName(serviceInfo.packageName, serviceInfo.name);
                Log.v(this, "ICS: " + componentName + ", user: " + entry.targetUserId);

                switch (getInCallServiceType(entry.serviceInfo, packageManager)) {
                    case IN_CALL_SERVICE_TYPE_DIALER_UI:
                        if (inCallUIService == null ||
                                inCallUIService.compareTo(componentName) > 0) {
                            inCallUIService = componentName;
                        }
                        break;

                    case IN_CALL_SERVICE_TYPE_SYSTEM_UI:
                        // skip, will be added manually
                        break;

                    case IN_CALL_SERVICE_TYPE_CAR_MODE_UI:
                        if (carModeInCallUIService == null ||
                                carModeInCallUIService.compareTo(componentName) > 0) {
                            carModeInCallUIService = componentName;
                        }
                        break;

                    case IN_CALL_SERVICE_TYPE_NON_UI:
                        nonUIInCallServices.add(componentName);
                        break;

                    case IN_CALL_SERVICE_TYPE_INVALID:
                        break;

                    default:
                        Log.w(this, "unexpected in-call service type");
                        break;
                }
            }
        }

        Log.i(this, "Car mode InCallService: %s", carModeInCallUIService);
        Log.i(this, "Dialer InCallService: %s", inCallUIService);

        // Adding the in-call services in order:
        // (1) The carmode in-call if carmode is on.
        // (2) The default-dialer in-call if not an emergency call
        // (3) The system-provided in-call
        List<ComponentName> orderedInCallUIServices = new LinkedList<>();
        if (shouldUseCarModeUI() && carModeInCallUIService != null) {
            orderedInCallUIServices.add(carModeInCallUIService);
        }
        if (!mCallsManager.hasEmergencyCall() && inCallUIService != null) {
            orderedInCallUIServices.add(inCallUIService);
        }
        orderedInCallUIServices.add(mSystemInCallComponentName);

        // TODO: Need to implement the fall-back logic in case the main UI in-call service rejects
        // the binding request.
        ComponentName inCallUIServiceToBind = orderedInCallUIServices.get(0);
        if (!bindToInCallService(inCallUIServiceToBind, call, "ui")) {
            Log.event(call, Log.Events.ERROR_LOG,
                    "InCallService system UI failed binding: " + inCallUIService);
        }
        mInCallUIComponentName = inCallUIServiceToBind;

        // Bind to the control InCallServices
        for (ComponentName componentName : nonUIInCallServices) {
            bindToInCallService(componentName, call, "control");
        }
    }

    /**
     * Binds to the specified InCallService.
     */
    private boolean bindToInCallService(ComponentName componentName, Call call, String tag) {
        if (mInCallServices.containsKey(componentName)) {
            Log.i(this, "An InCallService already exists: %s", componentName);
            return true;
        }

        if (mServiceConnections.containsKey(componentName)) {
            Log.w(this, "The service is already bound for this component %s", componentName);
            return true;
        }

        Intent intent = new Intent(InCallService.SERVICE_INTERFACE);
        intent.setComponent(componentName);
        if (call != null && !call.isIncoming()){
            intent.putExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                    call.getIntentExtras());
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                    call.getTargetPhoneAccount());
        }

        Log.i(this, "Attempting to bind to [%s] InCall %s, with %s", tag, componentName, intent);
        InCallServiceConnection inCallServiceConnection = new InCallServiceConnection();
        if (mContext.bindServiceAsUser(intent, inCallServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                    UserHandle.CURRENT)) {
            mServiceConnections.put(componentName, inCallServiceConnection);
            return true;
        }

        return false;
    }

    private boolean shouldUseCarModeUI() {
        return mSystemStateProvider.isCarMode();
    }

    /**
     * Returns the type of InCallService described by the specified serviceInfo.
     */
    private int getInCallServiceType(ServiceInfo serviceInfo, PackageManager packageManager) {
        // Verify that the InCallService requires the BIND_INCALL_SERVICE permission which
        // enforces that only Telecom can bind to it.
        boolean hasServiceBindPermission = serviceInfo.permission != null &&
                serviceInfo.permission.equals(
                        Manifest.permission.BIND_INCALL_SERVICE);
        if (!hasServiceBindPermission) {
            Log.w(this, "InCallService does not require BIND_INCALL_SERVICE permission: " +
                    serviceInfo.packageName);
            return IN_CALL_SERVICE_TYPE_INVALID;
        }

        if (mSystemInCallComponentName.getPackageName().equals(serviceInfo.packageName) &&
                mSystemInCallComponentName.getClassName().equals(serviceInfo.name)) {
            return IN_CALL_SERVICE_TYPE_SYSTEM_UI;
        }

        // Check to see if the service is a car-mode UI type by checking that it has the
        // CONTROL_INCALL_EXPERIENCE (to verify it is a system app) and that it has the
        // car-mode UI metadata.
        boolean hasControlInCallPermission = packageManager.checkPermission(
                Manifest.permission.CONTROL_INCALL_EXPERIENCE,
                serviceInfo.packageName) == PackageManager.PERMISSION_GRANTED;
        boolean isCarModeUIService = serviceInfo.metaData != null &&
                serviceInfo.metaData.getBoolean(
                        TelecomManager.METADATA_IN_CALL_SERVICE_CAR_MODE_UI, false) &&
                hasControlInCallPermission;
        if (isCarModeUIService) {
            return IN_CALL_SERVICE_TYPE_CAR_MODE_UI;
        }


        // Check to see that it is the default dialer package
        boolean isDefaultDialerPackage = Objects.equals(serviceInfo.packageName,
                DefaultDialerManager.getDefaultDialerApplication(
                    mContext, mCallsManager.getCurrentUserHandle().getIdentifier()));
        boolean isUIService = serviceInfo.metaData != null &&
                serviceInfo.metaData.getBoolean(
                        TelecomManager.METADATA_IN_CALL_SERVICE_UI, false);
        if (isDefaultDialerPackage && isUIService) {
            return IN_CALL_SERVICE_TYPE_DIALER_UI;
        }

        // Also allow any in-call service that has the control-experience permission (to ensure
        // that it is a system app) and doesn't claim to show any UI.
        if (hasControlInCallPermission && !isUIService) {
            return IN_CALL_SERVICE_TYPE_NON_UI;
        }

        // Anything else that remains, we will not bind to.
        Log.i(this, "Skipping binding to %s:%s, control: %b, car-mode: %b, ui: %b",
                serviceInfo.packageName, serviceInfo.name, hasControlInCallPermission,
                isCarModeUIService, isUIService);
        return IN_CALL_SERVICE_TYPE_INVALID;
    }

    private void adjustServiceBindingsForEmergency() {
        if (!Objects.equals(mInCallUIComponentName, mSystemInCallComponentName)) {
            // The connected UI is not the system UI, so lets check if we should switch them
            // if there exists an emergency number.
            if (mCallsManager.hasEmergencyCall()) {
                // Lets fake a failure here in order to trigger the switch to the system UI.
                onInCallServiceFailure(mInCallUIComponentName, "emergency adjust");
            }
        }
    }

    /**
     * Persists the {@link IInCallService} instance and starts the communication between
     * this class and in-call app by sending the first update to in-call app. This method is
     * called after a successful binding connection is established.
     *
     * @param componentName The service {@link ComponentName}.
     * @param service The {@link IInCallService} implementation.
     */
    private void onConnected(ComponentName componentName, IBinder service) {
        Trace.beginSection("onConnected: " + componentName);
        Log.i(this, "onConnected to %s", componentName);

        IInCallService inCallService = IInCallService.Stub.asInterface(service);
        mInCallServices.put(componentName, inCallService);

        try {
            inCallService.setInCallAdapter(
                    new InCallAdapter(
                            mCallsManager,
                            mCallIdMapper,
                            mLock,
                            componentName.getPackageName()));
        } catch (RemoteException e) {
            Log.e(this, e, "Failed to set the in-call adapter.");
            Trace.endSection();
            onInCallServiceFailure(componentName, "setInCallAdapter");
            return;
        }

        // Upon successful connection, send the state of the world to the service.
        List<Call> calls = orderCallsWithChildrenFirst(mCallsManager.getCalls());
        if (!calls.isEmpty()) {
            Log.i(this, "Adding %s calls to InCallService after onConnected: %s", calls.size(),
                    componentName);
            for (Call call : calls) {
                try {
                    // Track the call if we don't already know about it.
                    addCall(call);
                    inCallService.addCall(ParcelableCallUtils.toParcelableCall(
                            call,
                            true /* includeVideoProvider */,
                            mCallsManager.getPhoneAccountRegistrar()));
                } catch (RemoteException ignored) {
                }
            }
            try {
                inCallService.onCallAudioStateChanged(mCallsManager.getAudioState());
                inCallService.onCanAddCallChanged(mCallsManager.canAddCall());
            } catch (RemoteException ignored) {
            }
        } else {
            unbindFromServices();
        }
        Trace.endSection();
    }

    /**
     * Cleans up an instance of in-call app after the service has been unbound.
     *
     * @param disconnectedComponent The {@link ComponentName} of the service which disconnected.
     */
    private void onDisconnected(ComponentName disconnectedComponent) {
        Log.i(this, "onDisconnected from %s", disconnectedComponent);

        mInCallServices.remove(disconnectedComponent);
        if (mServiceConnections.containsKey(disconnectedComponent)) {
            // One of the services that we were bound to has unexpectedly disconnected.
            onInCallServiceFailure(disconnectedComponent, "onDisconnect");
        }
    }

    /**
     * Handles non-recoverable failures by the InCallService. This method performs cleanup and
     * special handling when the failure is to the UI InCallService.
     */
    private void onInCallServiceFailure(ComponentName componentName, String tag) {
        Log.i(this, "Cleaning up a failed InCallService [%s]: %s", tag, componentName);

        // We always clean up the connections here. Even in the case where we rebind to the UI
        // because binding is count based and we could end up double-bound.
        mInCallServices.remove(componentName);
        InCallServiceConnection serviceConnection = mServiceConnections.remove(componentName);
        if (serviceConnection != null) {
            // We still need to call unbind even though it disconnected.
            mContext.unbindService(serviceConnection);
        }

        if (Objects.equals(mInCallUIComponentName, componentName)) {
            if (!mCallsManager.hasAnyCalls()) {
                // No calls are left anyway. Lets just disconnect all of them.
                unbindFromServices();
                return;
            }

            // Whenever the UI crashes, we automatically revert to the System UI for the
            // remainder of the active calls.
            mInCallUIComponentName = mSystemInCallComponentName;
            bindToInCallService(mInCallUIComponentName, null, "reconnecting");
        }
    }

    /**
     * Informs all {@link InCallService} instances of the updated call information.
     *
     * @param call The {@link Call}.
     */
    private void updateCall(Call call) {
        updateCall(call, false /* videoProviderChanged */);
    }

    /**
     * Informs all {@link InCallService} instances of the updated call information.
     *
     * @param call The {@link Call}.
     * @param videoProviderChanged {@code true} if the video provider changed, {@code false}
     *      otherwise.
     */
    private void updateCall(Call call, boolean videoProviderChanged) {
        if (!mInCallServices.isEmpty()) {
            ParcelableCall parcelableCall = ParcelableCallUtils.toParcelableCall(
                    call,
                    videoProviderChanged /* includeVideoProvider */,
                    mCallsManager.getPhoneAccountRegistrar());
            Log.i(this, "Sending updateCall %s ==> %s", call, parcelableCall);
            List<ComponentName> componentsUpdated = new ArrayList<>();
            for (Map.Entry<ComponentName, IInCallService> entry : mInCallServices.entrySet()) {
                ComponentName componentName = entry.getKey();
                IInCallService inCallService = entry.getValue();
                componentsUpdated.add(componentName);
                try {
                    inCallService.updateCall(parcelableCall);
                } catch (RemoteException ignored) {
                }
            }
            Log.i(this, "Components updated: %s", componentsUpdated);
        }
    }

    /**
     * Adds the call to the list of calls tracked by the {@link InCallController}.
     * @param call The call to add.
     */
    private void addCall(Call call) {
        if (mCallIdMapper.getCallId(call) == null) {
            mCallIdMapper.addCall(call);
            call.addListener(mCallListener);
        }
    }

    private boolean isBoundToServices() {
        return !mInCallServices.isEmpty();
    }

    /**
     * Dumps the state of the {@link InCallController}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("mInCallServices (InCalls registered):");
        pw.increaseIndent();
        for (ComponentName componentName : mInCallServices.keySet()) {
            pw.println(componentName);
        }
        pw.decreaseIndent();

        pw.println("mServiceConnections (InCalls bound):");
        pw.increaseIndent();
        for (ComponentName componentName : mServiceConnections.keySet()) {
            pw.println(componentName);
        }
        pw.decreaseIndent();
    }

    public boolean doesConnectedDialerSupportRinging() {
        String ringingPackage =  null;
        if (mInCallUIComponentName != null) {
            ringingPackage = mInCallUIComponentName.getPackageName().trim();
        }

        if (TextUtils.isEmpty(ringingPackage)) {
            // The current in-call UI returned nothing, so lets use the default dialer.
            ringingPackage = DefaultDialerManager.getDefaultDialerApplication(
                    mContext, UserHandle.USER_CURRENT);
        }
        if (TextUtils.isEmpty(ringingPackage)) {
            return false;
        }

        Intent intent = new Intent(InCallService.SERVICE_INTERFACE)
            .setPackage(ringingPackage);
        List<ResolveInfo> entries = mContext.getPackageManager().queryIntentServicesAsUser(
                intent, PackageManager.GET_META_DATA,
                mCallsManager.getCurrentUserHandle().getIdentifier());
        if (entries.isEmpty()) {
            return false;
        }

        ResolveInfo info = entries.get(0);
        if (info.serviceInfo == null || info.serviceInfo.metaData == null) {
            return false;
        }

        return info.serviceInfo.metaData
                .getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_RINGING, false);
    }

    private List<Call> orderCallsWithChildrenFirst(Collection<Call> calls) {
        LinkedList<Call> parentCalls = new LinkedList<>();
        LinkedList<Call> childCalls = new LinkedList<>();
        for (Call call : calls) {
            if (call.getChildCalls().size() > 0) {
                parentCalls.add(call);
            } else {
                childCalls.add(call);
            }
        }
        childCalls.addAll(parentCalls);
        return childCalls;
    }
}
