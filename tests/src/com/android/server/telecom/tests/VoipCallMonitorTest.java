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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.app.ForegroundServiceDelegationOptions;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.telecom.PhoneAccountHandle;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.voip.VoipCallMonitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class VoipCallMonitorTest extends TelecomTestCase {
    private VoipCallMonitor mMonitor;
    private static final String PKG_NAME_1 = "telecom.voip.test1";
    private static final String PKG_NAME_2 = "telecom.voip.test2";
    private static final String CLS_NAME = "VoipActivity";
    private static final String ID_1 = "id1";
    private static final UserHandle USER_HANDLE_1 = new UserHandle(1);
    private static final long TIMEOUT = 5000L;

    @Mock private TelecomSystem.SyncRoot mLock;
    @Mock private ActivityManagerInternal mActivityManagerInternal;
    @Mock private NotificationListenerService mListenerService;

    private final PhoneAccountHandle mHandle1User1 = new PhoneAccountHandle(
            new ComponentName(PKG_NAME_1, CLS_NAME), ID_1, USER_HANDLE_1);
    private final PhoneAccountHandle mHandle2User1 = new PhoneAccountHandle(
            new ComponentName(PKG_NAME_2, CLS_NAME), ID_1, USER_HANDLE_1);

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mMonitor = new VoipCallMonitor(mContext, mLock);
        mActivityManagerInternal = mock(ActivityManagerInternal.class);
        mListenerService = mock(NotificationListenerService.class);
        mMonitor.setActivityManagerInternal(mActivityManagerInternal);
        mMonitor.setNotificationListenerService(mListenerService);
        doNothing().when(mListenerService).registerAsSystemService(eq(mContext),
                any(ComponentName.class), anyInt());
        mMonitor.startMonitor();
    }

    @SmallTest
    @Test
    public void testStartMonitorForOneCall() {
        Call call = createTestCall("testCall", mHandle1User1);
        IBinder service = mock(IBinder.class);

        ArgumentCaptor<ServiceConnection> captor = ArgumentCaptor.forClass(ServiceConnection.class);
        mMonitor.onCallAdded(call);
        verify(mActivityManagerInternal, timeout(TIMEOUT)).startForegroundServiceDelegate(any(
                ForegroundServiceDelegationOptions.class), captor.capture());
        ServiceConnection conn = captor.getValue();
        conn.onServiceConnected(mHandle1User1.getComponentName(), service);

        mMonitor.onCallRemoved(call);
        verify(mActivityManagerInternal, timeout(TIMEOUT)).stopForegroundServiceDelegate(eq(conn));
    }

    @SmallTest
    @Test
    public void testMonitorForTwoCallsOnSameHandle() {
        Call call1 = createTestCall("testCall1", mHandle1User1);
        Call call2 = createTestCall("testCall2", mHandle1User1);
        IBinder service = mock(IBinder.class);

        ArgumentCaptor<ServiceConnection> captor1 =
                ArgumentCaptor.forClass(ServiceConnection.class);
        mMonitor.onCallAdded(call1);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .startForegroundServiceDelegate(any(ForegroundServiceDelegationOptions.class),
                        captor1.capture());
        ServiceConnection conn1 = captor1.getValue();
        conn1.onServiceConnected(mHandle1User1.getComponentName(), service);

        ArgumentCaptor<ServiceConnection> captor2 =
                ArgumentCaptor.forClass(ServiceConnection.class);
        mMonitor.onCallAdded(call2);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(2))
                .startForegroundServiceDelegate(any(ForegroundServiceDelegationOptions.class),
                        captor2.capture());
        ServiceConnection conn2 = captor2.getValue();
        conn2.onServiceConnected(mHandle1User1.getComponentName(), service);

        mMonitor.onCallRemoved(call1);
        verify(mActivityManagerInternal, never()).stopForegroundServiceDelegate(
                any(ServiceConnection.class));
        mMonitor.onCallRemoved(call2);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .stopForegroundServiceDelegate(eq(conn2));
    }

    @SmallTest
    @Test
    public void testMonitorForTwoCallsOnDifferentHandle() {
        Call call1 = createTestCall("testCall1", mHandle1User1);
        Call call2 = createTestCall("testCall2", mHandle2User1);
        IBinder service = mock(IBinder.class);

        ArgumentCaptor<ServiceConnection> connCaptor1 = ArgumentCaptor.forClass(
                ServiceConnection.class);
        ArgumentCaptor<ForegroundServiceDelegationOptions> optionsCaptor1 =
                ArgumentCaptor.forClass(ForegroundServiceDelegationOptions.class);
        mMonitor.onCallAdded(call1);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .startForegroundServiceDelegate(optionsCaptor1.capture(), connCaptor1.capture());
        ForegroundServiceDelegationOptions options1 = optionsCaptor1.getValue();
        ServiceConnection conn1 = connCaptor1.getValue();
        conn1.onServiceConnected(mHandle1User1.getComponentName(), service);
        assertEquals(PKG_NAME_1, options1.getComponentName().getPackageName());

        ArgumentCaptor<ServiceConnection> connCaptor2 = ArgumentCaptor.forClass(
                ServiceConnection.class);
        ArgumentCaptor<ForegroundServiceDelegationOptions> optionsCaptor2 =
                ArgumentCaptor.forClass(ForegroundServiceDelegationOptions.class);
        mMonitor.onCallAdded(call2);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(2))
                .startForegroundServiceDelegate(optionsCaptor2.capture(), connCaptor2.capture());
        ForegroundServiceDelegationOptions options2 = optionsCaptor2.getValue();
        ServiceConnection conn2 = connCaptor2.getValue();
        conn2.onServiceConnected(mHandle2User1.getComponentName(), service);
        assertEquals(PKG_NAME_2, options2.getComponentName().getPackageName());

        mMonitor.onCallRemoved(call2);
        verify(mActivityManagerInternal).stopForegroundServiceDelegate(eq(conn2));
        mMonitor.onCallRemoved(call1);
        verify(mActivityManagerInternal).stopForegroundServiceDelegate(eq(conn1));
    }

    @SmallTest
    @Test
    public void testStopDelegation() {
        Call call1 = createTestCall("testCall1", mHandle1User1);
        Call call2 = createTestCall("testCall2", mHandle1User1);
        IBinder service = mock(IBinder.class);

        ArgumentCaptor<ServiceConnection> captor1 =
                ArgumentCaptor.forClass(ServiceConnection.class);
        mMonitor.onCallAdded(call1);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .startForegroundServiceDelegate(any(ForegroundServiceDelegationOptions.class),
                        captor1.capture());
        ServiceConnection conn1 = captor1.getValue();
        conn1.onServiceConnected(mHandle1User1.getComponentName(), service);

        ArgumentCaptor<ServiceConnection> captor2 =
                ArgumentCaptor.forClass(ServiceConnection.class);
        mMonitor.onCallAdded(call2);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(2))
                .startForegroundServiceDelegate(any(ForegroundServiceDelegationOptions.class),
                        captor2.capture());
        ServiceConnection conn2 = captor2.getValue();
        conn2.onServiceConnected(mHandle1User1.getComponentName(), service);

        mMonitor.stopFGSDelegation(mHandle1User1);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .stopForegroundServiceDelegate(eq(conn2));
        conn2.onServiceDisconnected(mHandle1User1.getComponentName());
        mMonitor.onCallRemoved(call1);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .stopForegroundServiceDelegate(any(ServiceConnection.class));
    }

    private Call createTestCall(String id, PhoneAccountHandle handle) {
        Call call = mock(Call.class);
        when(call.getTargetPhoneAccount()).thenReturn(handle);
        when(call.isTransactionalCall()).thenReturn(true);
        when(call.getExtras()).thenReturn(new Bundle());
        when(call.getId()).thenReturn(id);
        when(call.getCallingPackageIdentity()).thenReturn( new Call.CallingPackageIdentity() );
        return call;
    }
}
