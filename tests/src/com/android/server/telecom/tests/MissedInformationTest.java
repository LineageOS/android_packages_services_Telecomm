/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.provider.CallLog.Calls.AUTO_MISSED_EMERGENCY_CALL;
import static android.provider.CallLog.Calls.AUTO_MISSED_MAXIMUM_DIALING;
import static android.provider.CallLog.Calls.AUTO_MISSED_MAXIMUM_RINGING;
import static android.provider.CallLog.Calls.MISSED_REASON_NOT_MISSED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.IContentProvider;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;

import com.android.server.telecom.Analytics;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallIntentProcessor;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Map;

public class MissedInformationTest extends TelecomSystemTest {
    private static final int TEST_TIMEOUT_MILLIS = 1000;
    private static final String TEST_NUMBER = "650-555-1212";
    private static final String TEST_NUMBER_1 = "7";
    private static final String PACKAGE_NAME = "com.android.server.telecom.tests";
    @Mock ContentResolver mContentResolver;
    @Mock IContentProvider mContentProvider;
    @Mock Call mEmergencyCall;
    @Mock Analytics.CallInfo mCallInfo;
    private CallsManager mCallsManager;
    private CallIntentProcessor.AdapterImpl mAdapter;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mCallsManager = mTelecomSystem.getCallsManager();
        mAdapter = new CallIntentProcessor.AdapterImpl(mCallsManager.getDefaultDialerCache());
        when(mContentResolver.getPackageName()).thenReturn(PACKAGE_NAME);
        when(mContentResolver.acquireProvider(any(String.class))).thenReturn(mContentProvider);
        when(mContentProvider.call(any(String.class), any(String.class),
                any(String.class), any(Bundle.class))).thenReturn(new Bundle());
        doReturn(mContentResolver).when(mSpyContext).getContentResolver();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testNotMissedCall() throws Exception {
        IdPair testCall = startAndMakeActiveIncomingCall(
                TEST_NUMBER,
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall.mConnectionId, DisconnectCause.LOCAL);
        ContentValues values = verifyInsertionWithCapture();

        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();
        Analytics.CallInfoImpl callAnalytics = analyticsMap.get(testCall.mCallId);
        assertEquals(MISSED_REASON_NOT_MISSED, callAnalytics.missedReason);
        assertEquals(MISSED_REASON_NOT_MISSED, (int) values.getAsInteger(CallLog.Calls.MISSED_REASON));
    }

    @Test
    public void testEmergencyCallPlacing() throws Exception {
        Analytics.dumpToParcelableAnalytics();
        setUpEmergencyCall();
        mCallsManager.addCall(mEmergencyCall);
        assertTrue(mCallsManager.isInEmergencyCall());

        Intent intent = new Intent();
        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
               mPhoneAccountA0.getAccountHandle());
        mAdapter.processIncomingCallIntent(mCallsManager, intent);

        ContentValues values = verifyInsertionWithCapture();

        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();
        for (Analytics.CallInfoImpl ci : analyticsMap.values()) {
            assertEquals(AUTO_MISSED_EMERGENCY_CALL, ci.missedReason);
        }
        assertEquals(AUTO_MISSED_EMERGENCY_CALL,
                (int) values.getAsInteger(CallLog.Calls.MISSED_REASON));
    }

    @Test
    public void testMaximumDialingCalls() throws Exception {
        Analytics.dumpToParcelableAnalytics();
        IdPair testDialingCall = startAndMakeDialingOutgoingCall(
                TEST_NUMBER,
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        Intent intent = new Intent();
        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                mPhoneAccountA0.getAccountHandle());
        mAdapter.processIncomingCallIntent(mCallsManager, intent);

        ContentValues values = verifyInsertionWithCapture();

        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();
        for (String callId : analyticsMap.keySet()) {
            if (callId.equals(testDialingCall.mCallId)) {
                continue;
            }
            assertEquals(AUTO_MISSED_MAXIMUM_DIALING, analyticsMap.get(callId).missedReason);
        }
        assertEquals(AUTO_MISSED_MAXIMUM_DIALING,
                (int) values.getAsInteger(CallLog.Calls.MISSED_REASON));
    }

    @Test
    public void testMaximumRingingCalls() throws Exception {
        Analytics.dumpToParcelableAnalytics();
        IdPair testRingingCall = startAndMakeRingingIncomingCall(
                TEST_NUMBER,
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        Intent intent = new Intent();
        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                mPhoneAccountA0.getAccountHandle());
        mAdapter.processIncomingCallIntent(mCallsManager, intent);

        ContentValues values = verifyInsertionWithCapture();

        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();
        for (String callId : analyticsMap.keySet()) {
            if (callId.equals(testRingingCall.mCallId)) {
                continue;
            }
            assertEquals(AUTO_MISSED_MAXIMUM_RINGING, analyticsMap.get(callId).missedReason);
        }
        assertEquals(AUTO_MISSED_MAXIMUM_RINGING,
                (int) values.getAsInteger(CallLog.Calls.MISSED_REASON));
    }

    private ContentValues verifyInsertionWithCapture() {
        ArgumentCaptor<ContentValues> captor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mContentResolver, timeout(TEST_TIMEOUT_MILLIS))
                .insert(any(Uri.class), captor.capture());
        return captor.getValue();
    }

    private void setUpEmergencyCall() {
        when(mEmergencyCall.isEmergencyCall()).thenReturn(true);
        when(mEmergencyCall.getIntentExtras()).thenReturn(new Bundle());
        when(mEmergencyCall.getAnalytics()).thenReturn(mCallInfo);
        when(mEmergencyCall.getState()).thenReturn(CallState.ACTIVE);
        when(mEmergencyCall.getContext()).thenReturn(mSpyContext);
        when(mEmergencyCall.getHandle()).thenReturn(Uri.parse("tel:" + TEST_NUMBER));
    }
}
