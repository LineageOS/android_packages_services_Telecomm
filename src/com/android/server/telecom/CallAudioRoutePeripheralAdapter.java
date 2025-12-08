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

/**
 * A class that acts as a listener to things that could change call audio routing, namely
 * bluetooth status, wired headset status, and dock status.
 */
public class CallAudioRoutePeripheralAdapter implements WiredHeadsetManager.Listener,
        DockManager.Listener {

    private final CallAudioRouteAdapter mCallAudioAdapter;

    public CallAudioRoutePeripheralAdapter(
            CallAudioRouteAdapter callAudioRouteAdapter,
            WiredHeadsetManager wiredHeadsetManager,
            DockManager dockManager) {
        mCallAudioAdapter = callAudioRouteAdapter;
        wiredHeadsetManager.addListener(this);
        dockManager.addListener(this);
    }

    /**
      * Updates the audio route when the headset plugged in state changes. For example, if audio is
      * being routed over speakerphone and a headset is plugged in then switch to wired headset.
      */
    @Override
    public void onWiredHeadsetPluggedInChanged(boolean oldIsPluggedIn, boolean newIsPluggedIn) {
        if (!oldIsPluggedIn && newIsPluggedIn) {
            mCallAudioAdapter.sendMessageWithSessionInfo(
                    CallAudioRouteController.CONNECT_WIRED_HEADSET);
        } else if (oldIsPluggedIn && !newIsPluggedIn){
            mCallAudioAdapter.sendMessageWithSessionInfo(
                    CallAudioRouteController.DISCONNECT_WIRED_HEADSET);
        }
    }

    @Override
    public void onDockChanged(boolean isDocked) {
        mCallAudioAdapter.sendMessageWithSessionInfo(
                isDocked ? CallAudioRouteController.CONNECT_DOCK
                        : CallAudioRouteController.DISCONNECT_DOCK
        );
    }
}
