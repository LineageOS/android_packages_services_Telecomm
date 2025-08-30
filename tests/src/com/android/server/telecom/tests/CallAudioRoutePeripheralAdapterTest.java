/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.telecom.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.AsyncRingtonePlayer;
import com.android.server.telecom.CallAudioRoutePeripheralAdapter;
import com.android.server.telecom.CallAudioRouteController;
import com.android.server.telecom.DockManager;
import com.android.server.telecom.WiredHeadsetManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class CallAudioRoutePeripheralAdapterTest extends TelecomTestCase {
    CallAudioRoutePeripheralAdapter mAdapter;

    @Mock private CallAudioRouteController mCallAudioRouteController;
    @Mock private WiredHeadsetManager mWiredHeadsetManager;
    @Mock private DockManager mDockManager;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        mAdapter = new CallAudioRoutePeripheralAdapter(
                mCallAudioRouteController,
                mWiredHeadsetManager,
                mDockManager);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testOnWiredHeadsetPluggedInChangedNoChange() {
        mAdapter.onWiredHeadsetPluggedInChanged(false, false);
        mAdapter.onWiredHeadsetPluggedInChanged(true, true);
        verify(mCallAudioRouteController, never()).sendMessageWithSessionInfo(anyInt());
    }

    @SmallTest
    @Test
    public void testOnWiredHeadsetPluggedInChangedPlugged() {
        mAdapter.onWiredHeadsetPluggedInChanged(false, true);
        verify(mCallAudioRouteController).sendMessageWithSessionInfo(
                CallAudioRouteController.CONNECT_WIRED_HEADSET);
    }

    @SmallTest
    @Test
    public void testOnWiredHeadsetPluggedInChangedUnplugged() {
        mAdapter.onWiredHeadsetPluggedInChanged(true, false);
        verify(mCallAudioRouteController).sendMessageWithSessionInfo(
                CallAudioRouteController.DISCONNECT_WIRED_HEADSET);
    }

    @SmallTest
    @Test
    public void testOnDockChangedConnected() {
        mAdapter.onDockChanged(true);
        verify(mCallAudioRouteController).sendMessageWithSessionInfo(
                CallAudioRouteController.CONNECT_DOCK);
    }

    @SmallTest
    @Test
    public void testOnDockChangedDisconnected() {
        mAdapter.onDockChanged(false);
        verify(mCallAudioRouteController).sendMessageWithSessionInfo(
                CallAudioRouteController.DISCONNECT_DOCK);
    }
}
