/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.telecom.tests;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.telecom.bluetooth.BluetoothDeviceManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.bluetooth.BluetoothStateReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

@RunWith(JUnit4.class)
public class BluetoothDeviceManagerTest extends TelecomTestCase {
    @Mock BluetoothRouteManager mRouteManager;
    @Mock BluetoothHeadset mBluetoothHeadset;
    @Mock BluetoothAdapter mAdapter;
    @Mock BluetoothHearingAid mBluetoothHearingAid;
    @Mock BluetoothLeAudio mBluetoothLeAudio;

    BluetoothDeviceManager mBluetoothDeviceManager;
    BluetoothProfile.ServiceListener serviceListenerUnderTest;
    BluetoothStateReceiver receiverUnderTest;

    private BluetoothDevice device1;
    private BluetoothDevice device2;
    private BluetoothDevice device3;
    private BluetoothDevice device4;
    private BluetoothDevice device5;
    private BluetoothDevice device6;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        device1 = makeBluetoothDevice("00:00:00:00:00:01");
        // hearing aid
        device2 = makeBluetoothDevice("00:00:00:00:00:02");
        device3 = makeBluetoothDevice("00:00:00:00:00:03");
        // hearing aid
        device4 = makeBluetoothDevice("00:00:00:00:00:04");
        // le audio
        device5 = makeBluetoothDevice("00:00:00:00:00:05");
        device6 = makeBluetoothDevice("00:00:00:00:00:06");

        when(mBluetoothHearingAid.getHiSyncId(device2)).thenReturn(100L);
        when(mBluetoothHearingAid.getHiSyncId(device4)).thenReturn(100L);

        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        mBluetoothDeviceManager = new BluetoothDeviceManager(mContext, mAdapter);
        mBluetoothDeviceManager.setBluetoothRouteManager(mRouteManager);

        ArgumentCaptor<BluetoothProfile.ServiceListener> serviceCaptor =
                ArgumentCaptor.forClass(BluetoothProfile.ServiceListener.class);
        verify(mAdapter).getProfileProxy(eq(mContext),
                serviceCaptor.capture(), eq(BluetoothProfile.HEADSET));
        serviceListenerUnderTest = serviceCaptor.getValue();

        receiverUnderTest = new BluetoothStateReceiver(mBluetoothDeviceManager, mRouteManager);

        mBluetoothDeviceManager.setHeadsetServiceForTesting(mBluetoothHeadset);
        mBluetoothDeviceManager.setHearingAidServiceForTesting(mBluetoothHearingAid);
        mBluetoothDeviceManager.setLeAudioServiceForTesting(mBluetoothLeAudio);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testSingleDeviceConnectAndDisconnect() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        assertEquals(1, mBluetoothDeviceManager.getNumConnectedDevices());
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_DISCONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        assertEquals(0, mBluetoothDeviceManager.getNumConnectedDevices());
    }

    @SmallTest
    @Test
    public void testAddDeviceFailsWhenServicesAreNull() {
        mBluetoothDeviceManager.setHeadsetServiceForTesting(null);
        mBluetoothDeviceManager.setHearingAidServiceForTesting(null);

        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device2,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));

        assertEquals(0, mBluetoothDeviceManager.getNumConnectedDevices());
    }

    @SmallTest
    @Test
    public void testMultiDeviceConnectAndDisconnect() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device2,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        receiverUnderTest.onReceive(mContext,
                buildGroupNodeStatusChangedIntent(1, device5, BluetoothLeAudio.GROUP_NODE_ADDED));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_DISCONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device6,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        receiverUnderTest.onReceive(mContext,
                buildGroupNodeStatusChangedIntent(2, device6, BluetoothLeAudio.GROUP_NODE_ADDED));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device3,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_DISCONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        assertEquals(3, mBluetoothDeviceManager.getNumConnectedDevices());
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_DISCONNECTED, device3,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_DISCONNECTED, device6,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        assertEquals(1, mBluetoothDeviceManager.getNumConnectedDevices());
    }

    @SmallTest
    @Test
    public void testHearingAidDedup() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device2,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device4,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        assertEquals(3, mBluetoothDeviceManager.getNumConnectedDevices());
        assertEquals(2, mBluetoothDeviceManager.getUniqueConnectedDevices().size());
    }

    @SmallTest
    @Test
    public void testLeAudioDedup() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        receiverUnderTest.onReceive(mContext,
                buildGroupNodeStatusChangedIntent(1, device5, BluetoothLeAudio.GROUP_NODE_ADDED));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device6,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        receiverUnderTest.onReceive(mContext,
                buildGroupNodeStatusChangedIntent(1, device6, BluetoothLeAudio.GROUP_NODE_ADDED));
        assertEquals(3, mBluetoothDeviceManager.getNumConnectedDevices());
        assertEquals(2, mBluetoothDeviceManager.getUniqueConnectedDevices().size());
    }

    @SmallTest
    @Test
    public void testHeadsetServiceDisconnect() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device3,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device2,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        serviceListenerUnderTest.onServiceDisconnected(BluetoothProfile.HEADSET);

        verify(mRouteManager).onActiveDeviceChanged(isNull(),
                eq(BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        verify(mRouteManager).onDeviceLost(device1.getAddress());
        verify(mRouteManager).onDeviceLost(device3.getAddress());
        verify(mRouteManager, never()).onDeviceLost(device2.getAddress());
        assertNull(mBluetoothDeviceManager.getBluetoothHeadset());
        assertEquals(1, mBluetoothDeviceManager.getNumConnectedDevices());
    }

    @SmallTest
    @Test
    public void testHearingAidServiceDisconnect() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device3,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device2,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        serviceListenerUnderTest.onServiceDisconnected(BluetoothProfile.HEARING_AID);

        verify(mRouteManager).onActiveDeviceChanged(isNull(),
                eq(BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        verify(mRouteManager).onDeviceLost(device2.getAddress());
        verify(mRouteManager, never()).onDeviceLost(device1.getAddress());
        verify(mRouteManager, never()).onDeviceLost(device3.getAddress());
        assertNull(mBluetoothDeviceManager.getBluetoothHearingAid());
        assertEquals(2, mBluetoothDeviceManager.getNumConnectedDevices());
    }

    @SmallTest
    @Test
    public void testLeAudioServiceDisconnect() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device3,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothLeAudio.STATE_CONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        serviceListenerUnderTest.onServiceDisconnected(BluetoothProfile.LE_AUDIO);

        verify(mRouteManager).onActiveDeviceChanged(isNull(),
                eq(BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        verify(mRouteManager).onDeviceLost(device5.getAddress());
        verify(mRouteManager, never()).onDeviceLost(device1.getAddress());
        verify(mRouteManager, never()).onDeviceLost(device3.getAddress());
        assertNull(mBluetoothDeviceManager.getLeAudioService());
        assertEquals(2, mBluetoothDeviceManager.getNumConnectedDevices());
    }

    @SmallTest
    @Test
    public void testHearingAidChangesIgnoredWhenNotInCall() {
        receiverUnderTest.setIsInCall(false);
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device2,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        Intent activeDeviceChangedIntent =
                new Intent(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        activeDeviceChangedIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, device2);
        receiverUnderTest.onReceive(mContext, activeDeviceChangedIntent);

        verify(mRouteManager).onActiveDeviceChanged(device2,
                BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID);
        verify(mRouteManager, never()).sendMessage(BluetoothRouteManager.BT_AUDIO_IS_ON);
    }

    @SmallTest
    @Test
    public void testLeAudioGroupChangesIgnoredWhenNotInCall() {
        receiverUnderTest.setIsInCall(false);
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothLeAudio.STATE_CONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        Intent activeDeviceChangedIntent =
                        new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        activeDeviceChangedIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, device5);
        receiverUnderTest.onReceive(mContext, activeDeviceChangedIntent);

        verify(mRouteManager).onActiveDeviceChanged(device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO);
        verify(mRouteManager, never()).sendMessage(BluetoothRouteManager.BT_AUDIO_IS_ON);
    }

    @SmallTest
    @Test
    public void testConnectDisconnectAudioHeadset() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device1,
                        BluetoothDeviceManager.DEVICE_TYPE_HEADSET));
        when(mAdapter.setActiveDevice(nullable(BluetoothDevice.class),
                    eq(BluetoothAdapter.ACTIVE_DEVICE_ALL))).thenReturn(true);
        mBluetoothDeviceManager.connectAudio(device1.getAddress());
        verify(mAdapter).setActiveDevice(device1, BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL);
        verify(mAdapter, never()).setActiveDevice(nullable(BluetoothDevice.class),
                eq(BluetoothAdapter.ACTIVE_DEVICE_ALL));
        mBluetoothDeviceManager.disconnectAudio();
        verify(mBluetoothHeadset).disconnectAudio();
    }

    @SmallTest
    @Test
    public void testConnectDisconnectAudioHearingAid() {
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device2,
                        BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID));
        when(mAdapter.setActiveDevice(nullable(BluetoothDevice.class),
                eq(BluetoothAdapter.ACTIVE_DEVICE_ALL))).thenReturn(true);
        mBluetoothDeviceManager.connectAudio(device2.getAddress());
        verify(mAdapter).setActiveDevice(device2, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        verify(mBluetoothHeadset, never()).connectAudio();
        verify(mAdapter, never()).setActiveDevice(nullable(BluetoothDevice.class),
                eq(BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL));

        when(mAdapter.getActiveDevices(eq(BluetoothProfile.HEARING_AID)))
                .thenReturn(Arrays.asList(device2, null));

        mBluetoothDeviceManager.disconnectAudio();
        verify(mAdapter).removeActiveDevice(BluetoothAdapter.ACTIVE_DEVICE_ALL);
        verify(mBluetoothHeadset).disconnectAudio();
    }

    @SmallTest
    @Test
    public void testConnectDisconnectAudioLeAudio() {
        receiverUnderTest.setIsInCall(true);
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        receiverUnderTest.onReceive(mContext,
                buildGroupNodeStatusChangedIntent(1, device5, BluetoothLeAudio.GROUP_NODE_ADDED));
        when(mAdapter.setActiveDevice(nullable(BluetoothDevice.class),
                eq(BluetoothAdapter.ACTIVE_DEVICE_ALL))).thenReturn(true);
        mBluetoothDeviceManager.connectAudio(device5.getAddress());
        verify(mAdapter).setActiveDevice(device5, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        verify(mBluetoothHeadset, never()).connectAudio();
        verify(mAdapter, never()).setActiveDevice(nullable(BluetoothDevice.class),
                eq(BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL));

        mBluetoothDeviceManager.disconnectAudio();
        // TODO: Add a test here to verify that LE audio is de-selected
        // verify(mAdapter).removeActiveDevice(BluetoothAdapter.ACTIVE_DEVICE_ALL);
    }

    @SmallTest
    @Test
    public void testConnectEarbudLeAudio() {
        receiverUnderTest.setIsInCall(true);
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_CONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        receiverUnderTest.onReceive(mContext,
                buildGroupNodeStatusChangedIntent(1, device5, BluetoothLeAudio.GROUP_NODE_ADDED));
        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothLeAudio.STATE_CONNECTED, device6,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));
        receiverUnderTest.onReceive(mContext,
                buildGroupNodeStatusChangedIntent(1, device6, BluetoothLeAudio.GROUP_NODE_ADDED));
        when(mAdapter.setActiveDevice(nullable(BluetoothDevice.class),
                eq(BluetoothAdapter.ACTIVE_DEVICE_ALL))).thenReturn(true);
        mBluetoothDeviceManager.connectAudio(device5.getAddress());
        verify(mAdapter).setActiveDevice(device5, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        verify(mBluetoothHeadset, never()).connectAudio();
        verify(mAdapter, never()).setActiveDevice(nullable(BluetoothDevice.class),
                eq(BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL));

        when(mBluetoothLeAudio.getActiveDevices()).thenReturn(Arrays.asList(device5, device6));

        receiverUnderTest.onReceive(mContext,
                buildConnectionActionIntent(BluetoothHeadset.STATE_DISCONNECTED, device5,
                        BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO));

        mBluetoothDeviceManager.connectAudio(device6.getAddress());
        verify(mAdapter).setActiveDevice(device6, BluetoothAdapter.ACTIVE_DEVICE_ALL);
    }

    private Intent buildConnectionActionIntent(int state, BluetoothDevice device, int deviceType) {
        String intentString;

        switch (deviceType) {
            case BluetoothDeviceManager.DEVICE_TYPE_HEADSET:
                intentString = BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED;
                break;
            case BluetoothDeviceManager.DEVICE_TYPE_HEARING_AID:
                intentString = BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED;
                break;
            case BluetoothDeviceManager.DEVICE_TYPE_LE_AUDIO:
                intentString = BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED;
                break;
            default:
                return null;
        }

        Intent i = new Intent(intentString);
        i.putExtra(BluetoothHeadset.EXTRA_STATE, state);
        i.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        return i;
    }

    private Intent buildGroupNodeStatusChangedIntent(int groupId, BluetoothDevice device,
                int nodeStatus) {
        Intent i = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_GROUP_NODE_STATUS_CHANGED);
        i.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        i.putExtra(BluetoothLeAudio.EXTRA_LE_AUDIO_GROUP_ID, groupId);
        i.putExtra(BluetoothLeAudio.EXTRA_LE_AUDIO_GROUP_NODE_STATUS, nodeStatus);
        return i;
    }

    private Intent buildGroupStatusChangedIntent(int groupId, int groupStatus) {
        Intent i = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_GROUP_STATUS_CHANGED);
        i.putExtra(BluetoothLeAudio.EXTRA_LE_AUDIO_GROUP_ID, groupId);
        i.putExtra(BluetoothLeAudio.EXTRA_LE_AUDIO_GROUP_STATUS, groupStatus);
        return i;
    }

    private BluetoothDevice makeBluetoothDevice(String address) {
        Parcel p1 = Parcel.obtain();
        p1.writeString(address);
        p1.setDataPosition(0);
        BluetoothDevice device = BluetoothDevice.CREATOR.createFromParcel(p1);
        p1.recycle();
        return device;
    }
}
