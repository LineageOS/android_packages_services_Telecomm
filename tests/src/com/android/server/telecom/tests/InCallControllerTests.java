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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.telecom.ConnectionService;
import android.telecom.InCallService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.test.mock.MockContext;

import com.android.server.telecom.BluetoothHeadsetProxy;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.InCallController;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.R;
import com.android.server.telecom.SystemStateProvider;
import com.android.server.telecom.TelecomSystem;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.LinkedList;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyChar;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class InCallControllerTests extends TelecomTestCase {
    @Mock CallsManager mMockCallsManager;
    @Mock PhoneAccountRegistrar mMockPhoneAccountRegistrar;
    @Mock BluetoothHeadsetProxy mMockBluetoothHeadset;
    @Mock SystemStateProvider mMockSystemStateProvider;
    @Mock PackageManager mMockPackageManager;
    @Mock Call mMockCall;
    @Mock Resources mMockResources;
    @Mock MockContext mMockContext;

    private static final int CURRENT_USER_ID = 900973;
    private static final String DEF_PKG = "defpkg";
    private static final String DEF_CLASS = "defcls";
    private static final PhoneAccountHandle PA_HANDLE =
            new PhoneAccountHandle(new ComponentName("pa_pkg", "pa_cls"), "pa_id");

    private UserHandle mUserHandle = UserHandle.of(CURRENT_USER_ID);
    private InCallController mInCallController;
    private TelecomSystem.SyncRoot mLock;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        doReturn(mMockResources).when(mMockContext).getResources();
        doReturn(DEF_PKG).when(mMockResources).getString(R.string.ui_default_package);
        doReturn(DEF_CLASS).when(mMockResources).getString(R.string.incall_default_class);
        mInCallController = new InCallController(mMockContext, mLock, mMockCallsManager,
                mMockSystemStateProvider);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void testBindToService_NoServicesFound_IncomingCall() throws Exception {
        when(mMockCallsManager.getCurrentUserHandle()).thenReturn(mUserHandle);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockCallsManager.hasEmergencyCall()).thenReturn(false);
        when(mMockCall.isIncoming()).thenReturn(true);

        Intent queryIntent = new Intent(InCallService.SERVICE_INTERFACE);
        when(mMockPackageManager.queryIntentServicesAsUser(
                queryIntent, PackageManager.GET_META_DATA, CURRENT_USER_ID))
            .thenReturn(new LinkedList<ResolveInfo>());
        mInCallController.bindToServices(mMockCall);

        ArgumentCaptor<Intent> bindIntentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext).bindServiceAsUser(
                bindIntentCaptor.capture(),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        Intent bindIntent = bindIntentCaptor.getValue();
        assertEquals(InCallService.SERVICE_INTERFACE, bindIntent.getAction());
        assertEquals(DEF_PKG, bindIntent.getComponent().getPackageName());
        assertEquals(DEF_CLASS, bindIntent.getComponent().getClassName());
        assertNull(bindIntent.getExtras());
    }

    public void testBindToService_NoServicesFound_OutgoingCall() throws Exception {
        Bundle callExtras = new Bundle();
        callExtras.putBoolean("whatever", true);

        when(mMockCallsManager.getCurrentUserHandle()).thenReturn(mUserHandle);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockCallsManager.hasEmergencyCall()).thenReturn(false);
        when(mMockCall.isIncoming()).thenReturn(false);
        when(mMockCall.getTargetPhoneAccount()).thenReturn(PA_HANDLE);
        when(mMockCall.getIntentExtras()).thenReturn(callExtras);

        Intent queryIntent = new Intent(InCallService.SERVICE_INTERFACE);
        when(mMockPackageManager.queryIntentServicesAsUser(
                queryIntent, PackageManager.GET_META_DATA, CURRENT_USER_ID))
            .thenReturn(new LinkedList<ResolveInfo>());
        mInCallController.bindToServices(mMockCall);

        ArgumentCaptor<Intent> bindIntentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext).bindServiceAsUser(
                bindIntentCaptor.capture(),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        Intent bindIntent = bindIntentCaptor.getValue();
        assertEquals(InCallService.SERVICE_INTERFACE, bindIntent.getAction());
        assertEquals(DEF_PKG, bindIntent.getComponent().getPackageName());
        assertEquals(DEF_CLASS, bindIntent.getComponent().getClassName());
        assertEquals(PA_HANDLE, bindIntent.getExtras().getParcelable(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE));
        assertEquals(callExtras, bindIntent.getExtras().getParcelable(
                TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS));
    }
}
