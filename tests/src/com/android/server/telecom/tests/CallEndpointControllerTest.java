/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.net.Uri;
import android.os.ParcelUuid;
import android.telecom.CallEndpoint;
import android.telecom.PhoneAccountHandle;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telecom.ICallEndpointCallback;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallEndpointController;
import com.android.server.telecom.CallEndpointSessionTracker;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.InCallController;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.PhoneNumberUtilsAdapter;
import com.android.server.telecom.SystemStateHelper;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.ui.ToastFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@RunWith(JUnit4.class)
public class CallEndpointControllerTest extends TelecomTestCase {
    private static final String CLS = "StreamingInCallService";
    public static final String PKG_1 = "com.foo";
    private static final ComponentName ENDPOINT_CN_1 = new ComponentName(PKG_1, CLS);
    public static final CallEndpoint ENDPOINT_1 = new CallEndpoint(
            new ParcelUuid(UUID.randomUUID()), "endpoint 1",
            CallEndpoint.ENDPOINT_TYPE_TETHERED, ENDPOINT_CN_1);
    public static final String PKG_2 = "com.bar";
    private static final ComponentName ENDPOINT_CN_2 = new ComponentName(PKG_2, CLS);
    public static final CallEndpoint ENDPOINT_2 = new CallEndpoint(
            new ParcelUuid(UUID.randomUUID()), "endpoint 2",
            CallEndpoint.ENDPOINT_TYPE_TETHERED, ENDPOINT_CN_2);
    private static final ComponentName ACCOUNT_CN_1 = ComponentName
            .unflattenFromString("com.foo/.Blah");
    private static final PhoneAccountHandle SIM_1_HANDLE = new PhoneAccountHandle(
            ACCOUNT_CN_1, "Sim1");
    private static final Uri TEST_ADDRESS = Uri.parse("tel:555-1212");

    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };
    private CallEndpointController mCallEndpointController;
    private Call mCall;

    @Mock private CallsManager mCallsManager;
    @Mock private SystemStateHelper mSystemStateHelper;
    @Mock private PhoneNumberUtilsAdapter mPhoneNumberUtilsAdapter;
    @Mock private ClockProxy mClockProxy;
    @Mock private ToastFactory mToastFactory;
    @Mock private ICallEndpointCallback mCallEndpointCallback;
    @Mock private CallerInfoLookupHelper mCallerInfoLookupHelper;
    @Mock private PhoneAccountRegistrar mPhoneAccountRegistrar;
    @Mock private InCallController mInCallController;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        doReturn(mSystemStateHelper).when(mCallsManager).getSystemStateHelper();
        doReturn(mCallerInfoLookupHelper).when(mCallsManager).getCallerInfoLookupHelper();
        doReturn(mPhoneAccountRegistrar).when(mCallsManager).getPhoneAccountRegistrar();
        doReturn(mInCallController).when(mCallsManager).getInCallController();
        mCallEndpointController = new CallEndpointController(mLock, mCallsManager);
        doReturn(mCallEndpointController).when(mCallsManager).getCallEndpointController();
        mCall = getTestCall("1");
    }

    @Test
    @SmallTest
    public void testHandleEndpointFailure() throws Exception {
        List<CallEndpoint> added = new ArrayList<>();
        added.add(ENDPOINT_1);
        mCallEndpointController.registerCallEndpoints(added);
        mCallEndpointController.requestPlaceCall(mCall, ENDPOINT_1);
        CallEndpointSessionTracker tracker = mCallEndpointController.getSessionTrackerByCall(mCall);
        tracker.setRequestHandled(true);
        tracker.setCallEndpointCallback(mCallEndpointCallback);

        mCallEndpointController.getSystemStateListener().onPackageUninstalled(PKG_1);
        assertFalse("Call endpoint with uninstalled package should be unregistered.",
                mCallEndpointController.getCallEndpoints().contains(ENDPOINT_1));
        verify(mCallsManager).disconnectCall(eq(mCall));
        verify(mCallsManager, atLeastOnce()).updateAvailableCallEndpoints(any());
        verify(mCallEndpointCallback).onCallEndpointSessionDeactivated();
        assertTrue(mCallEndpointController.getCallEndpoints().isEmpty());
    }

    @Test
    @SmallTest
    public void testAvailableCallEndpointsPropagation() {
        List<CallEndpoint> added = new ArrayList<>();
        added.add(ENDPOINT_1);
        added.add(ENDPOINT_2);
        mCallEndpointController.registerCallEndpoints(added);
        mCall = getTestCall("2");
        assertEquals(mCall.getAvailableCallEndpoints(), new HashSet<>(added));

        mCallEndpointController.unregisterCallEndpoints(added);
        assertTrue(mCall.getAvailableCallEndpoints().isEmpty());
    }

    @Test
    @SmallTest
    public void testActiveCallEndpointChanged() {
        List<CallEndpoint> added = new ArrayList<>();
        added.add(ENDPOINT_1);
        mCallEndpointController.registerCallEndpoints(added);
        mCallEndpointController.requestPlaceCall(mCall, ENDPOINT_1);
        CallEndpointSessionTracker tracker = mCallEndpointController.getSessionTrackerByCall(mCall);
        tracker.setRequestHandled(true);
        tracker.setCallEndpointCallback(mCallEndpointCallback);

        assertTrue(mCall.isTetheredCall());
        assertEquals(ENDPOINT_1, mCall.getActiveCallEndpoint());

        mCall.setActiveCallEndpoint(null);
        assertFalse(mCall.isTetheredCall());
    }

    private Call getTestCall(String callId) {
        return new Call(callId, mContext, mCallsManager, mLock,
                null /* ConnectionServiceRepository */,
                mPhoneNumberUtilsAdapter, TEST_ADDRESS, null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE, Call.CALL_DIRECTION_INCOMING,
                false /* shouldAttachToExistingConnection*/,
                false /* isConference */,
                mClockProxy, mToastFactory);
    }
}
