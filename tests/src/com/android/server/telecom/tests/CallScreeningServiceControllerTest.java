/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.test.suitebuilder.annotation.SmallTest;

import android.telecom.CallerInfo;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallScreeningServiceHelper;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ParcelableCallUtils;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.RoleManagerAdapter;
import com.android.server.telecom.TelecomServiceImpl;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.callfiltering.CallFilterResultCallback;
import com.android.server.telecom.callfiltering.CallFilteringResult;
import com.android.server.telecom.callfiltering.CallFilteringResult.Builder;
import com.android.server.telecom.callfiltering.CallScreeningServiceController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class CallScreeningServiceControllerTest extends TelecomTestCase {

    private static final String CALL_ID = "u89prgt9ps78y5";
    private static final Uri TEST_HANDLE = Uri.parse("tel:1235551234");
    private static final String DEFAULT_DIALER_PACKAGE = "com.android.dialer";
    private static final String PKG_NAME = "com.android.services.telecom.tests";
    private static final String CLS_NAME = "CallScreeningService";
    private static final String APP_NAME = "Screeny McScreenface";
    private static final ComponentName CARRIER_DEFINED_CALL_SCREENING = new ComponentName(
            "com.android.carrier", "com.android.carrier.callscreeningserviceimpl");
    private static final ComponentName DEFAULT_DIALER_CALL_SCREENING = new ComponentName(
            "com.android.dialer", "com.android.dialer.callscreeningserviceimpl");
    private static final ComponentName USER_CHOSEN_CALL_SCREENING = new ComponentName(
            "com.android.userchosen", "com.android.userchosen.callscreeningserviceimpl");
    private static final CallFilteringResult PASS_RESULT = new Builder()
            .setShouldAllowCall(true)
            .setShouldReject(false)
            .setShouldAddToCallLog(true)
            .setShouldShowNotification(true)
            .build();
    @Mock
    Context mContext;
    @Mock
    Call mCall;
    @Mock
    CallsManager mCallsManager;
    @Mock
    RoleManagerAdapter mRoleManagerAdapter;
    @Mock
    CarrierConfigManager mCarrierConfigManager;
    @Mock
    PackageManager mPackageManager;
    @Mock
    ParcelableCallUtils.Converter mParcelableCallUtilsConverter;
    @Mock
    PhoneAccountRegistrar mPhoneAccountRegistrar;
    CallScreeningServiceHelper.AppLabelProxy mAppLabelProxy =
            new CallScreeningServiceHelper.AppLabelProxy() {
                @Override
                public CharSequence getAppLabel(String packageName) {
                    return APP_NAME;
                }
            };
    @Mock
    private CallFilterResultCallback mCallback;
    @Mock
    private TelecomManager mTelecomManager;
    @Mock
    private CallerInfoLookupHelper mCallerInfoLookupHelper;
    private ResolveInfo mResolveInfo;
    private TelecomServiceImpl.SettingsSecureAdapter mSettingsSecureAdapter =
            spy(new CallScreeningServiceFilterTest.SettingsSecureAdapterFake());
    private TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() {
    };

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mRoleManagerAdapter.getCallCompanionApps()).thenReturn(Collections.emptyList());
        when(mRoleManagerAdapter.getDefaultCallScreeningApp()).thenReturn(null);
        when(mRoleManagerAdapter.getCarModeDialerApp()).thenReturn(null);
        when(mCallsManager.getRoleManagerAdapter()).thenReturn(mRoleManagerAdapter);
        when(mCallsManager.getCurrentUserHandle()).thenReturn(UserHandle.CURRENT);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mCall.getId()).thenReturn(CALL_ID);

        setCarrierDefinedCallScreeningApplication();
        when(TelecomManager.from(mContext)).thenReturn(mTelecomManager);
        when(mTelecomManager.getDefaultDialerPackage()).thenReturn(DEFAULT_DIALER_PACKAGE);

        mResolveInfo = new ResolveInfo() {{
            serviceInfo = new ServiceInfo();
            serviceInfo.packageName = PKG_NAME;
            serviceInfo.name = CLS_NAME;
            serviceInfo.permission = Manifest.permission.BIND_SCREENING_SERVICE;
        }};

        when(mPackageManager.queryIntentServicesAsUser(nullable(Intent.class), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(mResolveInfo));
        when(mParcelableCallUtilsConverter.toParcelableCall(
                eq(mCall), anyBoolean(), eq(mPhoneAccountRegistrar))).thenReturn(null);
        when(mContext.bindServiceAsUser(nullable(Intent.class), nullable(ServiceConnection.class),
                anyInt(), eq(UserHandle.CURRENT))).thenReturn(true);
        when(mCall.getHandle()).thenReturn(TEST_HANDLE);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testAllAllowCall() {
        when(mRoleManagerAdapter.getDefaultCallScreeningApp()).thenReturn(
                USER_CHOSEN_CALL_SCREENING.getPackageName());
        CallScreeningServiceController controller = new CallScreeningServiceController(mContext,
                mCallsManager, mPhoneAccountRegistrar,
                mParcelableCallUtilsConverter, mLock,
                mSettingsSecureAdapter, mCallerInfoLookupHelper, mAppLabelProxy);

        controller.startFilterLookup(mCall, mCallback);

        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT,
                CARRIER_DEFINED_CALL_SCREENING.getPackageName());

        CallerInfoLookupHelper.OnQueryCompleteListener queryListener = verifyLookupStart();
        CallerInfo callerInfo = new CallerInfo();
        callerInfo.contactExists = false;
        queryListener.onCallerInfoQueryComplete(TEST_HANDLE, callerInfo);

        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT,
                DEFAULT_DIALER_CALL_SCREENING.getPackageName());
        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT, USER_CHOSEN_CALL_SCREENING
                .getPackageName());

        verify(mContext, times(3)).bindServiceAsUser(any(Intent.class), any(ServiceConnection
                        .class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        verify(mCallback).onCallFilteringComplete(eq(mCall), eq(PASS_RESULT));
    }

    @SmallTest
    @Test
    public void testCarrierAllowCallAndContactExists() {
        CallScreeningServiceController controller = new CallScreeningServiceController(mContext,
                mCallsManager,
                mPhoneAccountRegistrar, mParcelableCallUtilsConverter, mLock,
                mSettingsSecureAdapter, mCallerInfoLookupHelper, mAppLabelProxy);

        controller.startFilterLookup(mCall, mCallback);

        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT,
                CARRIER_DEFINED_CALL_SCREENING.getPackageName());

        CallerInfoLookupHelper.OnQueryCompleteListener queryListener = verifyLookupStart();
        CallerInfo callerInfo = new CallerInfo();
        callerInfo.contactExists = true;
        queryListener.onCallerInfoQueryComplete(TEST_HANDLE, callerInfo);

        verify(mContext, times(1)).bindServiceAsUser(any(Intent.class), any(ServiceConnection
                        .class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        verify(mCallback).onCallFilteringComplete(eq(mCall), eq(PASS_RESULT));
    }

    @SmallTest
    @Test
    public void testCarrierCallScreeningRejectCall() {
        CallScreeningServiceController controller = new CallScreeningServiceController(mContext,
                mCallsManager,
                mPhoneAccountRegistrar, mParcelableCallUtilsConverter, mLock,
                mSettingsSecureAdapter, mCallerInfoLookupHelper, mAppLabelProxy);

        controller.startFilterLookup(mCall, mCallback);

        CallFilteringResult expectedResult = new Builder()
                .setShouldAllowCall(false)
                .setShouldReject(true)
                .setShouldAddToCallLog(false)
                .setShouldShowNotification(true)
                .setCallBlockReason(Calls.BLOCK_REASON_CALL_SCREENING_SERVICE)
                .setCallScreeningAppName(APP_NAME)
                .setCallScreeningComponentName(
                        CARRIER_DEFINED_CALL_SCREENING.flattenToString())
                .build();

        controller.onCallScreeningFilterComplete(mCall, expectedResult,
                CARRIER_DEFINED_CALL_SCREENING.getPackageName());

        verify(mContext, times(1)).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        verify(mCallback)
                .onCallFilteringComplete(eq(mCall), eq(expectedResult));
    }

    @SmallTest
    @Test
    public void testDefaultDialerRejectCall() {
        when(mRoleManagerAdapter.getDefaultCallScreeningApp()).thenReturn(
                USER_CHOSEN_CALL_SCREENING.getPackageName());
        CallScreeningServiceController controller = new CallScreeningServiceController(mContext,
                mCallsManager,
                mPhoneAccountRegistrar, mParcelableCallUtilsConverter, mLock,
                mSettingsSecureAdapter, mCallerInfoLookupHelper, mAppLabelProxy);

        controller.startFilterLookup(mCall, mCallback);

        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT,
                CARRIER_DEFINED_CALL_SCREENING.getPackageName());

        CallerInfoLookupHelper.OnQueryCompleteListener queryListener = verifyLookupStart();
        CallerInfo callerInfo = new CallerInfo();
        callerInfo.contactExists = false;
        queryListener.onCallerInfoQueryComplete(TEST_HANDLE, callerInfo);

        CallFilteringResult.Builder resultBuilder = new Builder()
                .setShouldAllowCall(false)
                .setShouldReject(true)
                .setShouldShowNotification(true)
                .setCallBlockReason(Calls.BLOCK_REASON_CALL_SCREENING_SERVICE)
                .setCallScreeningAppName(APP_NAME)
                .setCallScreeningComponentName(DEFAULT_DIALER_CALL_SCREENING.flattenToString());

        CallFilteringResult providedResult = resultBuilder
                .setShouldAddToCallLog(false)
                .build();

        controller.onCallScreeningFilterComplete(mCall, providedResult,
                DEFAULT_DIALER_CALL_SCREENING.getPackageName());

        verify(mContext, times(3)).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        CallFilteringResult expectedResult = resultBuilder
                .setShouldAddToCallLog(true)
                .build();

        verify(mCallback).onCallFilteringComplete(eq(mCall), eq(expectedResult));
    }

    @SmallTest
    @Test
    public void testUserChosenRejectCall() {
        when(mRoleManagerAdapter.getDefaultCallScreeningApp()).thenReturn(
                USER_CHOSEN_CALL_SCREENING.getPackageName());
        CallScreeningServiceController controller = new CallScreeningServiceController(mContext,
                mCallsManager,
                mPhoneAccountRegistrar, mParcelableCallUtilsConverter, mLock,
                mSettingsSecureAdapter, mCallerInfoLookupHelper, mAppLabelProxy);

        controller.startFilterLookup(mCall, mCallback);

        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT,
                CARRIER_DEFINED_CALL_SCREENING.getPackageName());

        CallerInfoLookupHelper.OnQueryCompleteListener queryListener = verifyLookupStart();
        CallerInfo callerInfo = new CallerInfo();
        callerInfo.contactExists = false;
        queryListener.onCallerInfoQueryComplete(TEST_HANDLE, callerInfo);

        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT,
                DEFAULT_DIALER_CALL_SCREENING.getPackageName());

        CallFilteringResult.Builder resultBuilder = new Builder()
                .setShouldAllowCall(false)
                .setShouldReject(true)
                .setShouldShowNotification(true)
                .setCallBlockReason(Calls.BLOCK_REASON_CALL_SCREENING_SERVICE)
                .setCallScreeningAppName(APP_NAME)
                .setCallScreeningComponentName(DEFAULT_DIALER_CALL_SCREENING.flattenToString());
        CallFilteringResult providedResult = resultBuilder
                .setShouldAddToCallLog(false)
                .build();

        controller.onCallScreeningFilterComplete(mCall, providedResult,
                USER_CHOSEN_CALL_SCREENING.getPackageName());

        verify(mContext, times(3)).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        CallFilteringResult expectedResult = resultBuilder
                .setShouldAddToCallLog(true)
                .build();

        verify(mCallback).onCallFilteringComplete(eq(mCall), eq(expectedResult));
    }

    /**
     * This test verifies that where the default dialer role is filled by the same app as the caller
     * id and spam role, we will only bind to that call screening service once.
     */
    @SmallTest
    @Test
    public void testOnlyBindOnce() {
        // Assume the user chose the default dialer to also fill the caller id and spam role.
        when(mRoleManagerAdapter.getDefaultCallScreeningApp()).thenReturn(
                DEFAULT_DIALER_CALL_SCREENING.getPackageName());
        CallScreeningServiceController controller = new CallScreeningServiceController(mContext,
                mCallsManager,
                mPhoneAccountRegistrar, mParcelableCallUtilsConverter, mLock,
                mSettingsSecureAdapter, mCallerInfoLookupHelper, mAppLabelProxy);

        controller.startFilterLookup(mCall, mCallback);

        controller.onCallScreeningFilterComplete(mCall, PASS_RESULT,
                CARRIER_DEFINED_CALL_SCREENING.getPackageName());

        CallerInfoLookupHelper.OnQueryCompleteListener queryListener = verifyLookupStart();
        CallerInfo callerInfo = new CallerInfo();
        callerInfo.contactExists = false;
        queryListener.onCallerInfoQueryComplete(TEST_HANDLE, callerInfo);

        controller.onCallScreeningFilterComplete(mCall, new CallFilteringResult.Builder()
                        .setShouldAllowCall(false)
                        .setShouldReject(true)
                        .setShouldAddToCallLog(false)
                        .setShouldShowNotification(true)
                        .setCallBlockReason(CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE)
                        .setCallScreeningAppName(APP_NAME)
                        .setCallScreeningComponentName(
                                DEFAULT_DIALER_CALL_SCREENING.flattenToString())
                        .build(),
                DEFAULT_DIALER_CALL_SCREENING.getPackageName());

        controller.onCallScreeningFilterComplete(mCall, new CallFilteringResult.Builder()
                .setShouldAllowCall(false)
                .setShouldReject(true)
                .setShouldAddToCallLog(false)
                .setShouldShowNotification(true)
                .setCallBlockReason(CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE)
                .setCallScreeningAppName(APP_NAME)
                .setCallScreeningComponentName(DEFAULT_DIALER_CALL_SCREENING.flattenToString())
                .build(), USER_CHOSEN_CALL_SCREENING.getPackageName());

        // Expect to bind twice; once to the carrier defined service, and then again to the default
        // dialer.
        verify(mContext, times(2)).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.CURRENT));

        // Expect filtering to complete only a single time from the default dialer service.
        verify(mCallback, times(1)).onCallFilteringComplete(eq(mCall),
                eq(new CallFilteringResult.Builder()
                        .setShouldAllowCall(false)
                        .setShouldReject(true)
                        .setShouldAddToCallLog(true)
                        .setShouldShowNotification(true)
                        .setCallBlockReason(CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE)
                        .setCallScreeningAppName(APP_NAME)
                        .setCallScreeningComponentName(
                                DEFAULT_DIALER_CALL_SCREENING.flattenToString())
                        .build()));
    }

    private CallerInfoLookupHelper.OnQueryCompleteListener verifyLookupStart() {
        return verifyLookupStart(TEST_HANDLE);
    }

    private CallerInfoLookupHelper.OnQueryCompleteListener verifyLookupStart(Uri handle) {

        ArgumentCaptor<CallerInfoLookupHelper.OnQueryCompleteListener> captor =
                ArgumentCaptor.forClass(CallerInfoLookupHelper.OnQueryCompleteListener.class);
        verify(mCallerInfoLookupHelper).startLookup(eq(handle), captor.capture());
        return captor.getValue();
    }

    private void setCarrierDefinedCallScreeningApplication() {
        String carrierDefined = CARRIER_DEFINED_CALL_SCREENING.flattenToString();
        PersistableBundle bundle = new PersistableBundle();
        bundle.putString(CarrierConfigManager.KEY_CARRIER_CALL_SCREENING_APP_STRING,
                carrierDefined);
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
        when(mCarrierConfigManager.getConfig()).thenReturn(bundle);
    }

    public static class SettingsSecureAdapterFake implements
            TelecomServiceImpl.SettingsSecureAdapter {
        @Override
        public void putStringForUser(ContentResolver resolver, String name, String value,
                int userHandle) {

        }

        @Override
        public String getStringForUser(ContentResolver resolver, String name, int userHandle) {
            return USER_CHOSEN_CALL_SCREENING.flattenToString();
        }
    }
}
