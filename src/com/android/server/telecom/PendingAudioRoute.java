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

import static com.android.server.telecom.CallAudioRouteAdapter.PENDING_ROUTE_FAILED;
import static com.android.server.telecom.CallAudioRouteAdapter.SWITCH_BASELINE_ROUTE;
import static com.android.server.telecom.CallAudioRouteController.INCLUDE_BLUETOOTH_IN_BASELINE;

import android.bluetooth.BluetoothDevice;
import android.media.AudioManager;
import android.telecom.Log;
import android.util.ArraySet;
import android.util.Pair;

import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.Set;

/**
 * Used to represent the intermediate state during audio route switching.
 * Usually, audio route switching start with a communication device setting request to audio
 * framework and will be completed with corresponding success broadcasts or messages. Instance of
 * this class is responsible for tracking the pending success signals according to the original
 * audio route and the destination audio route of this switching.
 */
public class PendingAudioRoute {
    private CallAudioRouteController mCallAudioRouteController;
    private AudioManager mAudioManager;
    private BluetoothRouteManager mBluetoothRouteManager;
    private FeatureFlags mFeatureFlags;
    /**
     * The {@link AudioRoute} that this pending audio switching started with
     */
    private AudioRoute mOrigRoute;
    /**
     * The expected destination {@link AudioRoute} of this pending audio switching, can be changed
     * by new switching request during the ongoing switching
     */
    private AudioRoute mDestRoute;
    private Set<Pair<Integer, String>> mPendingMessages;
    private boolean mActive;
    /**
     * The device that has been set for communication by Telecom
     */
    private @AudioRoute.AudioRouteType int mCommunicationDeviceType = AudioRoute.TYPE_INVALID;

    PendingAudioRoute(CallAudioRouteController controller, AudioManager audioManager,
            BluetoothRouteManager bluetoothRouteManager, FeatureFlags featureFlags) {
        mCallAudioRouteController = controller;
        mAudioManager = audioManager;
        mBluetoothRouteManager = bluetoothRouteManager;
        mFeatureFlags = featureFlags;
        mPendingMessages = new ArraySet<>();
        mActive = false;
        mCommunicationDeviceType = AudioRoute.TYPE_INVALID;
    }

    /**
     * Sets the originating route information, and begins the process of transitioning OUT of the
     * originating route.
     * Note: We also pass in whether the destination route is going to be active.  This is so that
     * {@link AudioRoute#onOrigRouteAsPendingRoute(boolean, PendingAudioRoute, AudioManager,
     * BluetoothRouteManager)} knows whether or not the destination route will be active or not and
     * can determine whether or not it needs to call {@link AudioManager#clearCommunicationDevice()}
     * or not.  To optimize audio performance we only need to clear the communication device if the
     * end result is going to be that we are in an inactive state.
     * @param isOriginActive Whether the origin is active.
     * @param origRoute The origin.
     * @param isDestActive Whether the destination will be active.
     */
    void setOrigRoute(boolean isOriginActive, AudioRoute origRoute, boolean isDestActive,
            boolean isScoAlreadyConnected) {
        mActive = isDestActive;
        origRoute.onOrigRouteAsPendingRoute(isOriginActive, this, mAudioManager,
                mBluetoothRouteManager, isScoAlreadyConnected);
        mOrigRoute = origRoute;
    }

    public AudioRoute getOrigRoute() {
        return mOrigRoute;
    }

    void setDestRoute(boolean active, AudioRoute destRoute, BluetoothDevice device,
            boolean isScoAlreadyConnected) {
        destRoute.onDestRouteAsPendingRoute(active, this, device,
                mAudioManager, mBluetoothRouteManager, isScoAlreadyConnected);
        mActive = active;
        mDestRoute = destRoute;
    }

    public AudioRoute getDestRoute() {
        return mDestRoute;
    }

    public void addMessage(int message, String bluetoothDevice) {
        mPendingMessages.add(new Pair<>(message, bluetoothDevice));
    }

    public void onMessageReceived(Pair<Integer, String> message, String btAddressToExclude) {
        Log.i(this, "onMessageReceived: message - %s", message);
        if (message.first == PENDING_ROUTE_FAILED) {
            // Fallback to base route
            if (mFeatureFlags.telecomMetricsSupport()) {
                mCallAudioRouteController.fallBack(btAddressToExclude);
            } else {
                mCallAudioRouteController.sendMessageWithSessionInfo(
                        SWITCH_BASELINE_ROUTE, INCLUDE_BLUETOOTH_IN_BASELINE, btAddressToExclude);
            }
            return;
        }

        // Removes the first occurrence of the specified message from this list, if it is present.
        mPendingMessages.remove(message);
        evaluatePendingState();
    }

    public void evaluatePendingState() {
        if (mPendingMessages.isEmpty()) {
            mCallAudioRouteController.sendMessageWithSessionInfo(
                    CallAudioRouteAdapter.EXIT_PENDING_ROUTE);
        } else {
            Log.i(this, "evaluatePendingState: mPendingMessages - %s", mPendingMessages);
        }
    }

    public void clearPendingMessages() {
        mPendingMessages.clear();
    }

    public void clearPendingMessage(Pair<Integer, String> message) {
        mPendingMessages.remove(message);
    }

    public Set<Pair<Integer, String>> getPendingMessages() {
        return mPendingMessages;
    }

    /**
     * Whether the destination {@link #getDestRoute()} will be active or not.
     * @return {@code true} if destination will be active, {@code false} otherwise.
     */
    public boolean isActive() {
        return mActive;
    }

    public @AudioRoute.AudioRouteType int getCommunicationDeviceType() {
        return mCommunicationDeviceType;
    }

    public void setCommunicationDeviceType(
            @AudioRoute.AudioRouteType int communicationDeviceType) {
        mCommunicationDeviceType = communicationDeviceType;
    }

    public void overrideDestRoute(AudioRoute route) {
        mDestRoute = route;
    }

    public FeatureFlags getFeatureFlags() {
        return mFeatureFlags;
    }

    @Override
    public String toString() {
        return "PendingAudioRoute{" +
                ", mOrigRoute=" + mOrigRoute +
                ", mDestRoute=" + mDestRoute +
                ", mActive=" + mActive +
                ", mCommunicationDeviceType=" + mCommunicationDeviceType +
                '}';
    }
}
