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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.DefaultDialerCache;
import com.android.server.telecom.RoleManagerAdapter;
import com.android.server.telecom.TelecomSystem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class DefaultDialerCacheTest extends TelecomTestCase {

    private static final String DIALER1 = "com.android.dialer";
    private static final String DIALER2 = "xyz.abc.dialer";
    private static final String DIALER3 = "aaa.bbb.ccc.ddd";
    private static final int USER0_ID = 0;
    private static final int USER1_ID = 1;
    private static final int USER2_ID = 2;
    private static final UserHandle USER0 = new UserHandle(USER0_ID);
    private static final UserHandle USER1 = new UserHandle(USER1_ID);
    private static final UserHandle USER2 = new UserHandle(USER2_ID);

    private static final int DELAY_TOLERANCE = 100;

    private DefaultDialerCache mDefaultDialerCache;
    private ContentObserver mDefaultDialerSettingObserver;
    private BroadcastReceiver mPackageChangeReceiver;
    private BroadcastReceiver mUserRemovedReceiver;

    @Mock
    private DefaultDialerCache.DefaultDialerManagerAdapter mMockDefaultDialerManager;
    @Mock
    private RoleManagerAdapter mRoleManagerAdapter;
    @Mock private Context mUserContext;
    @Mock private UserHandle mDefaultUserHandle;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        when(mFeatureFlags.resolveHiddenDependenciesTwo()).thenReturn(true);
        when(mContext.createContextAsUser(any(UserHandle.class), anyInt()))
                .thenReturn(mUserContext);

        ArgumentCaptor<BroadcastReceiver> packageReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        mDefaultDialerCache = new DefaultDialerCache(
                mContext, mMockDefaultDialerManager, mRoleManagerAdapter,
                new TelecomSystem.SyncRoot() {}, mFeatureFlags);

        verify(mUserContext, times(2)).registerReceiver(
                packageReceiverCaptor.capture(), any(IntentFilter.class),
                eq(Context.RECEIVER_NOT_EXPORTED));
        // Receive the first receiver that was captured, the package change receiver.
        mPackageChangeReceiver = packageReceiverCaptor.getAllValues().getFirst();

        ArgumentCaptor<BroadcastReceiver> userRemovedReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(
                userRemovedReceiverCaptor.capture(), any(IntentFilter.class));
        mUserRemovedReceiver = userRemovedReceiverCaptor.getAllValues().getFirst();

        mDefaultDialerSettingObserver = mDefaultDialerCache.getContentObserver();

        when(mMockDefaultDialerManager.getDefaultDialerApplication(any(Context.class), eq(USER0)))
                .thenReturn(DIALER1);
        when(mMockDefaultDialerManager.getDefaultDialerApplication(any(Context.class), eq(USER1)))
                .thenReturn(DIALER2);
        when(mMockDefaultDialerManager.getDefaultDialerApplication(any(Context.class), eq(USER2)))
                .thenReturn(DIALER3);
        when(mRoleManagerAdapter.getDefaultDialerApp(eq(USER0_ID))).thenReturn(DIALER1);
        when(mRoleManagerAdapter.getDefaultDialerAppFromUserHandle(eq(USER0))).thenReturn(DIALER1);
        when(mRoleManagerAdapter.getDefaultDialerApp(eq(USER1_ID))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerAppFromUserHandle(eq(USER1))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerApp(eq(USER2_ID))).thenReturn(DIALER3);
        when(mRoleManagerAdapter.getDefaultDialerAppFromUserHandle(eq(USER2))).thenReturn(DIALER3);

        // This test implies user 0 is the default user
        when(mDefaultUserHandle.getIdentifier()).thenReturn(USER0_ID);
        when(mContext.getUser()).thenReturn(mDefaultUserHandle);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testThreeUsers() {
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication());
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication());
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));

        // User 0: 2 calls for default dialer + 2 calls for explicit USER0 = 4 calls:
        verify(mRoleManagerAdapter, times(4)).getDefaultDialerAppFromUserHandle(eq(USER0));
        // User 1: 2 calls for explicit USER1 = 2 calls:
        verify(mRoleManagerAdapter, times(2)).getDefaultDialerAppFromUserHandle(eq(USER1));
        // User 2: 2 calls for explicit USER2 = 2 calls:
        verify(mRoleManagerAdapter, times(2)).getDefaultDialerAppFromUserHandle(eq(USER2));
    }

    @SmallTest
    @Test
    public void testDialer1PackageChanged() {
        // Populate the caches first
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));

        Intent packageChangeIntent = new Intent(Intent.ACTION_PACKAGE_CHANGED,
                Uri.fromParts("package", DIALER1, null));
        when(mRoleManagerAdapter.getDefaultDialerApp(eq(USER0_ID))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerAppFromUserHandle(eq(USER0))).thenReturn(DIALER2);

        mPackageChangeReceiver.onReceive(mContext, packageChangeIntent);
        waitForHandlerAction(mDefaultDialerCache.mHandler, DELAY_TOLERANCE);

        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER0));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER1));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER2));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER0_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER1_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER2_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER0));

        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER0));
    }

    @SmallTest
    @Test
    public void testRandomOtherPackageChanged() {
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));

        Intent packageChangeIntent = new Intent(Intent.ACTION_PACKAGE_CHANGED,
                Uri.fromParts("package", "red.orange.blue", null));
        mPackageChangeReceiver.onReceive(mContext, packageChangeIntent);
        waitForHandlerAction(mDefaultDialerCache.mHandler, DELAY_TOLERANCE);

        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER0));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER1));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER2));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER0_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER1_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER2_ID));
    }

    @SmallTest
    @Test
    public void testUserRemoved() {
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));

        Intent userRemovalIntent = new Intent(Intent.ACTION_USER_REMOVED);
        userRemovalIntent.putExtra(Intent.EXTRA_USER_HANDLE, USER0_ID);
        mUserRemovedReceiver.onReceive(mContext, userRemovalIntent);

        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));

        verify(mRoleManagerAdapter, times(2)).getDefaultDialerAppFromUserHandle(eq(USER0));
        verify(mRoleManagerAdapter, times(2)).getDefaultDialerAppFromUserHandle(eq(USER1));
    }

    @SmallTest
    @Test
    public void testPackageRemovedWithoutReplace() {
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));

        Intent packageChangeIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED,
                Uri.fromParts("package", DIALER1, null));
        packageChangeIntent.putExtra(Intent.EXTRA_REPLACING, false);

        mPackageChangeReceiver.onReceive(mContext, packageChangeIntent);
        waitForHandlerAction(mDefaultDialerCache.mHandler, DELAY_TOLERANCE);

        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER0));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER1));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER2));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER0_ID));
    }

    @SmallTest
    @Test
    public void testPackageAdded() {
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));

        Intent packageChangeIntent = new Intent(Intent.ACTION_PACKAGE_ADDED,
                Uri.fromParts("package", "ppp.qqq.zzz", null));

        mPackageChangeReceiver.onReceive(mContext, packageChangeIntent);
        waitForHandlerAction(mDefaultDialerCache.mHandler, DELAY_TOLERANCE);

        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER0));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER1));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER2));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER0_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER1_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER2_ID));
    }

    @SmallTest
    @Test
    public void testPackageRemovedWithReplace() {
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));

        Intent packageChangeIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED,
                Uri.fromParts("package", DIALER1, null));
        packageChangeIntent.putExtra(Intent.EXTRA_REPLACING, true);

        mPackageChangeReceiver.onReceive(mContext, packageChangeIntent);
        waitForHandlerAction(mDefaultDialerCache.mHandler, DELAY_TOLERANCE);

        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER0));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER1));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER2));
    }

    @SmallTest
    @Test
    public void testDefaultDialerSettingChanged() {
        assertEquals(DIALER1, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER3, mDefaultDialerCache.getDefaultDialerApplication(USER2));

        when(mRoleManagerAdapter.getDefaultDialerApp(eq(USER0_ID))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerApp(eq(USER1_ID))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerApp(eq(USER2_ID))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerAppFromUserHandle(eq(USER0))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerAppFromUserHandle(eq(USER1))).thenReturn(DIALER2);
        when(mRoleManagerAdapter.getDefaultDialerAppFromUserHandle(eq(USER2))).thenReturn(DIALER2);

        mDefaultDialerSettingObserver.onChange(false);
        waitForHandlerAction(mDefaultDialerCache.mHandler, DELAY_TOLERANCE);

        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER0));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER1));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerAppFromUserHandle(eq(USER2));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER0_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER1_ID));
        verify(mRoleManagerAdapter, times(1)).getDefaultDialerApp(eq(USER2_ID));

        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER0));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER1));
        assertEquals(DIALER2, mDefaultDialerCache.getDefaultDialerApplication(USER2));
    }
}
