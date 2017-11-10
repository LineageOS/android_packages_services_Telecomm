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

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.os.Parcel;
import android.telecom.Log;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.os.SomeArgs;
import com.android.server.telecom.BluetoothHeadsetProxy;
import com.android.server.telecom.CallAudioModeStateMachine;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.bluetooth.BluetoothDeviceManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;

import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BluetoothRouteManagerTest extends StateMachineTestBase<BluetoothRouteManager> {
    private enum ListenerUpdate {
        DEVICE_LIST_CHANGED, DEVICE_AVAILABLE, DEVICE_UNAVAILABLE,
        AUDIO_CONNECTED, AUDIO_DISCONNECTED
    }

    private static class BluetoothRouteTestParametersBuilder {
        private String name;
        private String initialBluetoothState;
        private BluetoothDevice initialDevice;
        private BluetoothDevice audioOnDevice;
        private int messageType;
        private String messageDevice;
        private ListenerUpdate[] expectedListenerUpdates;
        private int expectedBluetoothInteraction;
        private String expectedConnectionAddress;
        private String expectedFinalStateName;
        private BluetoothDevice[] connectedDevices;

        public BluetoothRouteTestParametersBuilder setName(String name) {
            this.name = name;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setInitialBluetoothState(
                String initialBluetoothState) {
            this.initialBluetoothState = initialBluetoothState;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setInitialDevice(BluetoothDevice
                initialDevice) {
            this.initialDevice = initialDevice;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setMessageType(int messageType) {
            this.messageType = messageType;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setMessageDevice(String messageDevice) {
            this.messageDevice = messageDevice;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setExpectedListenerUpdates(
                ListenerUpdate... expectedListenerUpdates) {
            this.expectedListenerUpdates = expectedListenerUpdates;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setExpectedBluetoothInteraction(
                int expectedBluetoothInteraction) {
            this.expectedBluetoothInteraction = expectedBluetoothInteraction;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setExpectedConnectionAddress(String
                expectedConnectionAddress) {
            this.expectedConnectionAddress = expectedConnectionAddress;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setExpectedFinalStateName(
                String expectedFinalStateName) {
            this.expectedFinalStateName = expectedFinalStateName;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setConnectedDevices(
                BluetoothDevice... connectedDevices) {
            this.connectedDevices = connectedDevices;
            return this;
        }

        public BluetoothRouteTestParametersBuilder setAudioOnDevice(BluetoothDevice device) {
            this.audioOnDevice = device;
            return this;
        }

        public BluetoothRouteTestParameters build() {
            return new BluetoothRouteTestParameters(name,
                    initialBluetoothState,
                    initialDevice,
                    messageType,
                    expectedListenerUpdates,
                    expectedBluetoothInteraction,
                    expectedConnectionAddress,
                    expectedFinalStateName,
                    connectedDevices,
                    messageDevice,
                    audioOnDevice);
        }
    }

    private static class BluetoothRouteTestParameters extends TestParameters {
        public String name;
        public String initialBluetoothState; // One of the state names or prefixes from BRM.
        public BluetoothDevice initialDevice; // null if we start from AudioOff
        public BluetoothDevice audioOnDevice; // The device (if any) that is active
        public int messageType; // Any of the commands from the state machine
        public String messageDevice; // The device that should be specified in the message.
        public ListenerUpdate[] expectedListenerUpdates; // what the listener should expect.
        public int expectedBluetoothInteraction; // NONE, CONNECT, or DISCONNECT
        public String expectedConnectionAddress; // Expected device to connect to.
        public String expectedFinalStateName; // Expected name of the final state.
        public BluetoothDevice[] connectedDevices; // array of connected devices

        public BluetoothRouteTestParameters(String name, String initialBluetoothState,
                BluetoothDevice initialDevice, int messageType, ListenerUpdate[]
                expectedListenerUpdates, int expectedBluetoothInteraction, String
                expectedConnectionAddress, String expectedFinalStateName,
                BluetoothDevice[] connectedDevices, String messageDevice,
                BluetoothDevice audioOnDevice) {
            this.name = name;
            this.initialBluetoothState = initialBluetoothState;
            this.initialDevice = initialDevice;
            this.messageType = messageType;
            this.expectedListenerUpdates = expectedListenerUpdates;
            this.expectedBluetoothInteraction = expectedBluetoothInteraction;
            this.expectedConnectionAddress = expectedConnectionAddress;
            this.expectedFinalStateName = expectedFinalStateName;
            this.connectedDevices = connectedDevices;
            this.messageDevice = messageDevice;
            this.audioOnDevice = audioOnDevice;
        }

        @Override
        public String toString() {
            return "BluetoothRouteTestParameters{" +
                    "name='" + name + '\'' +
                    ", initialBluetoothState='" + initialBluetoothState + '\'' +
                    ", initialDevice=" + initialDevice +
                    ", messageType=" + messageType +
                    ", messageDevice='" + messageDevice + '\'' +
                    ", expectedListenerUpdate=" + expectedListenerUpdates +
                    ", expectedBluetoothInteraction=" + expectedBluetoothInteraction +
                    ", expectedConnectionAddress='" + expectedConnectionAddress + '\'' +
                    ", expectedFinalStateName='" + expectedFinalStateName + '\'' +
                    ", connectedDevices=" + Arrays.toString(connectedDevices) +
                    '}';
        }
    }

    private static final int NONE = 1;
    private static final int CONNECT = 2;
    private static final int DISCONNECT = 3;

    @Mock private BluetoothDeviceManager mDeviceManager;
    @Mock private BluetoothHeadsetProxy mHeadsetProxy;
    @Mock private Timeouts.Adapter mTimeoutsAdapter;
    @Mock private BluetoothRouteManager.BluetoothStateListener mListener;

    private BluetoothDevice device1;
    private BluetoothDevice device2;
    private BluetoothDevice device3;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();

        device1 = makeBluetoothDevice("00:00:00:00:00:01");
        device2 = makeBluetoothDevice("00:00:00:00:00:02");
        device3 = makeBluetoothDevice("00:00:00:00:00:03");
    }

    @LargeTest
    public void testTransitions() throws Throwable {
        List<BluetoothRouteTestParameters> testCases = generateTestCases();
        parametrizedTestStateMachine(testCases);
    }

    @SmallTest
    public void testConnectHfpRetryWhileNotConnected() {
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_OFF_STATE_NAME, null);
        setupConnectedDevices(new BluetoothDevice[]{device1}, null);
        when(mTimeoutsAdapter.getRetryBluetoothConnectAudioBackoffMillis(
                nullable(ContentResolver.class))).thenReturn(0L);
        when(mHeadsetProxy.connectAudio(nullable(String.class))).thenReturn(false);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, null);
        // Wait 3 times: for the first connection attempt, the retry attempt,
        // the second retry, and once more to make sure there are only three attempts.
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        verify(mHeadsetProxy, times(3)).connectAudio(device1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_OFF_STATE_NAME, sm.getCurrentState().getName());
        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    @SmallTest
    public void testConnectHfpRetryWhileConnectedToAnotherDevice() {
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX, device1);
        setupConnectedDevices(new BluetoothDevice[]{device1, device2}, null);
        when(mTimeoutsAdapter.getRetryBluetoothConnectAudioBackoffMillis(
                nullable(ContentResolver.class))).thenReturn(0L);
        when(mHeadsetProxy.connectAudio(nullable(String.class))).thenReturn(false);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, device2.getAddress());
        // Wait 3 times: the first connection attempt is accounted for in executeRoutingAction,
        // so wait twice for the retry attempt, again to make sure there are only three attempts,
        // and once more for good luck.
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        verify(mHeadsetProxy, times(3)).connectAudio(device2.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device1.getAddress(),
                sm.getCurrentState().getName());
        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    @SmallTest
    public void testProperFallbackOrder1() {
        // Device 1, 2, 3 are connected in that order. Device 1 is activated, then device 2.
        // Disconnect device 2, verify fallback to device 1. Disconnect device 1, fallback to
        // device 3.
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_OFF_STATE_NAME, null);
        setupConnectedDevices(new BluetoothDevice[]{device3, device2, device1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, device1.getAddress());
        verify(mHeadsetProxy, times(1)).connectAudio(device1.getAddress());

        setupConnectedDevices(new BluetoothDevice[]{device3, device2, device1}, device1);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, device1.getAddress());

        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, device2.getAddress());
        verify(mHeadsetProxy, times(1)).connectAudio(device2.getAddress());

        setupConnectedDevices(new BluetoothDevice[]{device3, device2, device1}, device2);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, device2.getAddress());
        // Disconnect device 2
        setupConnectedDevices(new BluetoothDevice[]{device3, device1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, device2.getAddress());
        // Verify that we've fallen back to device 1
        verify(mHeadsetProxy, times(2)).connectAudio(device1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device1.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{device3, device1}, device1);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, device1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device1.getAddress(),
                sm.getCurrentState().getName());

        // Disconnect device 1
        setupConnectedDevices(new BluetoothDevice[]{device3}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, device1.getAddress());
        // Verify that we've fallen back to device 3
        verify(mHeadsetProxy, times(1)).connectAudio(device3.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device3.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{device3}, device3);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, device3.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device3.getAddress(),
                sm.getCurrentState().getName());

        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    @SmallTest
    public void testProperFallbackOrder2() {
        // Device 1, 2, 3 are connected in that order. Device 3 is activated.
        // Disconnect device 3, verify fallback to device 2. Disconnect device 2, fallback to
        // device 1.
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_OFF_STATE_NAME, null);
        setupConnectedDevices(new BluetoothDevice[]{device3, device2, device1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, device3.getAddress());
        verify(mHeadsetProxy, times(1)).connectAudio(device3.getAddress());

        setupConnectedDevices(new BluetoothDevice[]{device3, device2, device1}, device3);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, device3.getAddress());

        // Disconnect device 2
        setupConnectedDevices(new BluetoothDevice[]{device2, device1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, device3.getAddress());
        // Verify that we've fallen back to device 2
        verify(mHeadsetProxy, times(1)).connectAudio(device2.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device2.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{device2, device1}, device2);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, device2.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device2.getAddress(),
                sm.getCurrentState().getName());

        // Disconnect device 2
        setupConnectedDevices(new BluetoothDevice[]{device1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, device2.getAddress());
        // Verify that we've fallen back to device 1
        verify(mHeadsetProxy, times(1)).connectAudio(device1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device1.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{device1}, device1);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, device1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device1.getAddress(),
                sm.getCurrentState().getName());

        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    @Override
    protected void runParametrizedTestCase(TestParameters _params) {
        BluetoothRouteTestParameters params = (BluetoothRouteTestParameters) _params;
        BluetoothRouteManager sm = setupStateMachine(
                params.initialBluetoothState, params.initialDevice);

        setupConnectedDevices(params.connectedDevices, params.audioOnDevice);

        // Go through the utility methods for these two messages
        if (params.messageType == BluetoothRouteManager.NEW_DEVICE_CONNECTED) {
            sm.onDeviceAdded(params.messageDevice);
            waitForStateMachineActionCompletion(sm, CallAudioModeStateMachine.RUN_RUNNABLE);
        } else if (params.messageType == BluetoothRouteManager.LOST_DEVICE) {
            sm.onDeviceLost(params.messageDevice);
            waitForStateMachineActionCompletion(sm, CallAudioModeStateMachine.RUN_RUNNABLE);
        } else {
            executeRoutingAction(sm, params.messageType, params.messageDevice);
        }

        assertEquals(params.expectedFinalStateName, sm.getCurrentState().getName());

        for (ListenerUpdate lu : params.expectedListenerUpdates) {
            switch (lu) {
                case DEVICE_LIST_CHANGED:
                    verify(mListener).onBluetoothDeviceListChanged();
                    break;
                case DEVICE_AVAILABLE:
                    verify(mListener).onBluetoothDeviceAvailable();
                    break;
                case DEVICE_UNAVAILABLE:
                    verify(mListener).onBluetoothDeviceUnavailable();
                    break;
                case AUDIO_CONNECTED:
                    verify(mListener).onBluetoothAudioConnected();
                    break;
                case AUDIO_DISCONNECTED:
                    verify(mListener).onBluetoothAudioDisconnected();
                    break;
            }
        }

        switch (params.expectedBluetoothInteraction) {
            case NONE:
                verify(mHeadsetProxy, never()).connectAudio(nullable(String.class));
                verify(mHeadsetProxy, never()).disconnectAudio();
                break;
            case CONNECT:
                verify(mHeadsetProxy).connectAudio(params.expectedConnectionAddress);
                verify(mHeadsetProxy, never()).disconnectAudio();
                break;
            case DISCONNECT:
                verify(mHeadsetProxy, never()).connectAudio(nullable(String.class));
                verify(mHeadsetProxy).disconnectAudio();
                break;
        }

        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    private BluetoothRouteManager setupStateMachine(String initialState,
            BluetoothDevice initialDevice) {
        resetMocks(true);
        BluetoothRouteManager sm = new BluetoothRouteManager(mContext,
                new TelecomSystem.SyncRoot() { }, mDeviceManager, mTimeoutsAdapter);
        sm.setListener(mListener);
        sm.setInitialStateForTesting(initialState, initialDevice);
        waitForStateMachineActionCompletion(sm, BluetoothRouteManager.RUN_RUNNABLE);
        resetMocks(false);
        return sm;
    }

    private void setupConnectedDevices(BluetoothDevice[] devices, BluetoothDevice activeDevice) {
        when(mDeviceManager.getNumConnectedDevices()).thenReturn(devices.length);
        when(mDeviceManager.getConnectedDevices()).thenReturn(Arrays.asList(devices));
        when(mHeadsetProxy.getConnectedDevices()).thenReturn(Arrays.asList(devices));
        if (activeDevice != null) {
            when(mHeadsetProxy.isAudioConnected(eq(activeDevice))).thenReturn(true);
        }
        doAnswer(invocation -> {
            BluetoothDevice first = getFirstExcluding(devices,
                    (String) invocation.getArguments()[0]);
            return first == null ? null : first.getAddress();
        }).when(mDeviceManager).getMostRecentlyConnectedDevice(nullable(String.class));
    }

    private void executeRoutingAction(BluetoothRouteManager brm, int message, String device) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = device;
        brm.sendMessage(message, args);
        waitForStateMachineActionCompletion(brm, CallAudioModeStateMachine.RUN_RUNNABLE);
    }

    public static BluetoothDevice makeBluetoothDevice(String address) {
        Parcel p1 = Parcel.obtain();
        p1.writeString(address);
        p1.setDataPosition(0);
        BluetoothDevice device = BluetoothDevice.CREATOR.createFromParcel(p1);
        p1.recycle();
        return device;
    }

    private void resetMocks(boolean createNewMocks) {
        reset(mDeviceManager, mListener, mHeadsetProxy, mTimeoutsAdapter);
        if (createNewMocks) {
            mDeviceManager = mock(BluetoothDeviceManager.class);
            mListener = mock(BluetoothRouteManager.BluetoothStateListener.class);
            mHeadsetProxy = mock(BluetoothHeadsetProxy.class);
            mTimeoutsAdapter = mock(Timeouts.Adapter.class);
        }
        when(mDeviceManager.getHeadsetService()).thenReturn(mHeadsetProxy);
        when(mHeadsetProxy.connectAudio(nullable(String.class))).thenReturn(true);
        when(mTimeoutsAdapter.getRetryBluetoothConnectAudioBackoffMillis(
                nullable(ContentResolver.class))).thenReturn(100000L);
        when(mTimeoutsAdapter.getBluetoothPendingTimeoutMillis(
                nullable(ContentResolver.class))).thenReturn(100000L);
    }

    private static BluetoothDevice getFirstExcluding(
            BluetoothDevice[] devices, String excludeAddress) {
        for (BluetoothDevice x : devices) {
            if (!Objects.equals(excludeAddress, x.getAddress())) {
                return x;
            }
        }
        return null;
    }

    private List<BluetoothRouteTestParameters> generateTestCases() {
        List<BluetoothRouteTestParameters> result = new ArrayList<>();
        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("New device connected while audio off")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .setInitialDevice(null)
                .setConnectedDevices(device1)
                .setMessageType(BluetoothRouteManager.NEW_DEVICE_CONNECTED)
                .setMessageDevice(device1.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.DEVICE_AVAILABLE)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedConnectionAddress(null)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Nonspecific connection request while audio off.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .setInitialDevice(null)
                .setConnectedDevices(device2, device1)
                .setMessageType(BluetoothRouteManager.CONNECT_HFP)
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(CONNECT)
                .setExpectedConnectionAddress(device2.getAddress())
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device2.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Connection to a device succeeds after pending")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setAudioOnDevice(device2)
                .setConnectedDevices(device2, device1)
                .setMessageType(BluetoothRouteManager.HFP_IS_ON)
                .setMessageDevice(device2.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedConnectionAddress(null)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device2.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Device loses HFP audio but remains connected. No fallback.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2)
                .setMessageType(BluetoothRouteManager.HFP_LOST)
                .setMessageDevice(device2.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_DISCONNECTED)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedConnectionAddress(null)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Device loses HFP audio but remains connected. Fallback.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2, device1, device3)
                .setMessageType(BluetoothRouteManager.HFP_LOST)
                .setMessageDevice(device2.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(CONNECT)
                .setExpectedConnectionAddress(device1.getAddress())
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device1.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Switch active devices")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2, device1, device3)
                .setMessageType(BluetoothRouteManager.CONNECT_HFP)
                .setMessageDevice(device3.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(CONNECT)
                .setExpectedConnectionAddress(device3.getAddress())
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device3.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Switch to another device before first device has connected")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2, device1, device3)
                .setMessageType(BluetoothRouteManager.CONNECT_HFP)
                .setMessageDevice(device3.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(CONNECT)
                .setExpectedConnectionAddress(device3.getAddress())
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device3.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Device gets disconnected while active. No fallback.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices()
                .setMessageType(BluetoothRouteManager.LOST_DEVICE)
                .setMessageDevice(device2.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_DISCONNECTED,
                        ListenerUpdate.DEVICE_LIST_CHANGED, ListenerUpdate.DEVICE_UNAVAILABLE)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedConnectionAddress(null)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Device gets disconnected while active. Fallback.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device3)
                .setMessageType(BluetoothRouteManager.LOST_DEVICE)
                .setMessageDevice(device2.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED,
                        ListenerUpdate.DEVICE_LIST_CHANGED)
                .setExpectedBluetoothInteraction(CONNECT)
                .setExpectedConnectionAddress(device3.getAddress())
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device3.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Connection to device2 times out but device 1 still connected.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2, device1)
                .setAudioOnDevice(device1)
                .setMessageType(BluetoothRouteManager.CONNECTION_TIMEOUT)
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device1.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("device1 somehow becomes active when device2 is still pending.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2, device1)
                .setAudioOnDevice(device1)
                .setMessageType(BluetoothRouteManager.HFP_IS_ON)
                .setMessageDevice(device1.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device1.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Device gets disconnected while pending. Fallback.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device3)
                .setMessageType(BluetoothRouteManager.LOST_DEVICE)
                .setMessageDevice(device2.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED,
                        ListenerUpdate.DEVICE_LIST_CHANGED)
                .setExpectedBluetoothInteraction(CONNECT)
                .setExpectedConnectionAddress(device3.getAddress())
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + device3.getAddress())
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Device gets disconnected while pending. No fallback.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices()
                .setMessageType(BluetoothRouteManager.LOST_DEVICE)
                .setMessageDevice(device2.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_DISCONNECTED,
                        ListenerUpdate.DEVICE_LIST_CHANGED)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Audio routing requests HFP disconnection while a device is active")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2, device3)
                .setMessageType(BluetoothRouteManager.DISCONNECT_HFP)
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_DISCONNECTED)
                .setExpectedBluetoothInteraction(DISCONNECT)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Audio routing requests HFP disconnection while a device is pending")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX)
                .setInitialDevice(device2)
                .setConnectedDevices(device2, device3)
                .setMessageType(BluetoothRouteManager.DISCONNECT_HFP)
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_DISCONNECTED)
                .setExpectedBluetoothInteraction(DISCONNECT)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .build());

        result.add(new BluetoothRouteTestParametersBuilder()
                .setName("Bluetooth turns itself on.")
                .setInitialBluetoothState(BluetoothRouteManager.AUDIO_OFF_STATE_NAME)
                .setInitialDevice(null)
                .setConnectedDevices(device2, device3)
                .setMessageType(BluetoothRouteManager.HFP_IS_ON)
                .setMessageDevice(device3.getAddress())
                .setExpectedListenerUpdates(ListenerUpdate.AUDIO_CONNECTED)
                .setExpectedBluetoothInteraction(NONE)
                .setExpectedFinalStateName(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + device3.getAddress())
                .build());

        return result;
    }
}
