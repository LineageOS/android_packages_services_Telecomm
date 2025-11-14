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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.settings.BlockedNumbersActivity;
import com.android.server.telecom.settings.BlockedNumbersUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BlockedNumbersUtilTests extends TelecomTestCase {
    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mFeatureFlags.resolveHiddenDependenciesTwo()).thenReturn(true);
    }

    @SmallTest
    @Test
    public void testPostNotification() {
        BlockedNumbersUtil.updateEmergencyCallNotification(mContext, true, mFeatureFlags);
        NotificationManager mgr = mComponentContextFixture.getNotificationManager();
        Context userContext = mock(Context.class);
        when(mContext.createContextAsUser(any(UserHandle.class), eq(0)))
                .thenReturn(userContext);
        when(userContext.getSystemService(eq(NotificationManager.class)))
                .thenReturn(mgr);
        verify(mgr).notify(isNull(), anyInt(), any(Notification.class));
    }

    @SmallTest
    @Test
    public void testDismissNotification() {
        BlockedNumbersUtil.updateEmergencyCallNotification(mContext, false, mFeatureFlags);
        NotificationManager mgr = mComponentContextFixture.getNotificationManager();
        Context userContext = mock(Context.class);
        when(mContext.createContextAsUser(any(UserHandle.class), eq(0)))
                .thenReturn(userContext);
        when(userContext.getSystemService(eq(NotificationManager.class)))
                .thenReturn(mgr);
        verify(mgr).cancel(isNull(), anyInt());
    }

    /**
     * Verify that when Telephony isn't present we can still check if a number is an emergency
     * number in the {@link BlockedNumbersActivity} and not crash.
     */
    @SmallTest
    @Test
    public void testBlockedNumbersActivityEmergencyCheckWithNoTelephony() {
        when(mComponentContextFixture.getTelephonyManager().isEmergencyNumber(anyString()))
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        assertFalse(BlockedNumbersActivity.isEmergencyNumber(mContext, "911"));
    }
}
