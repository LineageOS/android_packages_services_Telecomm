/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.server.telecom.tests;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.telecom.PackageRemovedReceiver;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.UserHandleWrapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class PackageRemovedReceiverTest extends TelecomTestCase {
    private static final String TEST_PACKAGE_NAME = "com.example.testapp";
    private static final int TEST_UID = 10123;

    @Mock private PhoneAccountRegistrar mMockPhoneAccountRegistrar;
    @Mock private Handler mMockBackgroundHandler;
    @Mock private Intent mMockIntent;
    @Mock private Uri mMockUri;
    @Mock private UserHandle mMockUserHandle;
    @Mock private UserHandleWrapper mMockUserHandleWrapper;

    private PackageRemovedReceiver mReceiver;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.openMocks(this);

        when(mMockUserHandleWrapper.getUserHandleForUid(TEST_UID)).thenReturn(mMockUserHandle);
        when(mMockUserHandleWrapper.getUserHandleForUid(Process.INVALID_UID)).thenReturn(null);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    private void setupIntent(String action, Uri data, String packageName, int uid) {
        when(mMockIntent.getAction()).thenReturn(action);
        when(mMockIntent.getData()).thenReturn(data);
        if (data != null) {
            when(mMockUri.getSchemeSpecificPart()).thenReturn(packageName);
        }
        when(mMockIntent.getIntExtra(Intent.EXTRA_UID, Process.INVALID_UID)).thenReturn(uid);
    }

    @Test
    public void onReceive_nullAction_shouldDoNothing() {
        mReceiver = new PackageRemovedReceiver(mMockPhoneAccountRegistrar,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(null, mMockUri, TEST_PACKAGE_NAME, TEST_UID);

        mReceiver.onReceive(mContext, mMockIntent);

        verifyNoInteractions(mMockBackgroundHandler);
        verifyNoInteractions(mMockPhoneAccountRegistrar);
    }

    @Test
    public void onReceive_wrongAction_shouldDoNothing() {
        mReceiver = new PackageRemovedReceiver(mMockPhoneAccountRegistrar,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(Intent.ACTION_PACKAGE_ADDED, mMockUri, TEST_PACKAGE_NAME, TEST_UID);

        mReceiver.onReceive(mContext, mMockIntent);

        verifyNoInteractions(mMockBackgroundHandler);
        verifyNoInteractions(mMockPhoneAccountRegistrar);
    }

    @Test
    public void onReceive_packageFullyRemoved_nullUri_shouldLogAndReturn() {
        mReceiver = new PackageRemovedReceiver(mMockPhoneAccountRegistrar,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(Intent.ACTION_PACKAGE_FULLY_REMOVED, null, TEST_PACKAGE_NAME, TEST_UID);

        mReceiver.onReceive(mContext, mMockIntent);

        verifyNoInteractions(mMockBackgroundHandler);
        verifyNoInteractions(mMockPhoneAccountRegistrar);
    }

    @Test
    public void onReceive_packageFullyRemoved_nullPackageName_shouldLogAndReturn() {
        mReceiver = new PackageRemovedReceiver(mMockPhoneAccountRegistrar,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(Intent.ACTION_PACKAGE_FULLY_REMOVED, mMockUri, null, TEST_UID);

        mReceiver.onReceive(mContext, mMockIntent);

        verifyNoInteractions(mMockBackgroundHandler);
        verifyNoInteractions(mMockPhoneAccountRegistrar);
    }

    @Test
    public void onReceive_packageFullyRemoved_emptyPackageName_shouldLogAndReturn() {
        mReceiver = new PackageRemovedReceiver(mMockPhoneAccountRegistrar,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(Intent.ACTION_PACKAGE_FULLY_REMOVED, mMockUri, "", TEST_UID);

        mReceiver.onReceive(mContext, mMockIntent);

        verifyNoInteractions(mMockBackgroundHandler);
        verifyNoInteractions(mMockPhoneAccountRegistrar);
    }

    @Test
    public void onReceive_packageFullyRemoved_invalidUid_shouldLogAndReturn() {
        mReceiver = new PackageRemovedReceiver(mMockPhoneAccountRegistrar,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(Intent.ACTION_PACKAGE_FULLY_REMOVED, mMockUri,
                TEST_PACKAGE_NAME, Process.INVALID_UID);

        mReceiver.onReceive(mContext, mMockIntent);

        verifyNoInteractions(mMockBackgroundHandler);
        verifyNoInteractions(mMockPhoneAccountRegistrar);
    }

    @Test
    public void onReceive_validIntent_withBackgroundHandler_shouldPostToHandler() {
        mReceiver = new PackageRemovedReceiver(mMockPhoneAccountRegistrar,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(Intent.ACTION_PACKAGE_FULLY_REMOVED, mMockUri, TEST_PACKAGE_NAME, TEST_UID);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(mMockBackgroundHandler.post(runnableCaptor.capture())).thenReturn(true);

        mReceiver.onReceive(mContext, mMockIntent);

        verify(mMockBackgroundHandler).post(Mockito.any(Runnable.class));

        Runnable postedRunnable = runnableCaptor.getValue();
        postedRunnable.run();

        verify(mMockPhoneAccountRegistrar).clearAccounts(TEST_PACKAGE_NAME, mMockUserHandle);
    }

    @Test
    public void onReceive_validIntent_withBackgroundHandler_nullRegistrar_shouldLogButNotCrash() {
        mReceiver = new PackageRemovedReceiver(null,
                mMockBackgroundHandler, mMockUserHandleWrapper);
        setupIntent(Intent.ACTION_PACKAGE_FULLY_REMOVED, mMockUri, TEST_PACKAGE_NAME, TEST_UID);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(mMockBackgroundHandler.post(runnableCaptor.capture())).thenReturn(true);

        mReceiver.onReceive(mContext, mMockIntent);

        verify(mMockBackgroundHandler).post(Mockito.any(Runnable.class));
        runnableCaptor.getValue().run();

        verifyNoInteractions(mMockPhoneAccountRegistrar);
    }
}