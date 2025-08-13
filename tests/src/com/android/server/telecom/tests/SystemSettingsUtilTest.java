/*
 * Copyright (C) 2025 The Android Open Source Project
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.SystemSettingsUtil;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.flags.FeatureFlagsImpl;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class SystemSettingsUtilTest extends TelecomTestCase {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private FeatureFlags mFeatureFlags;
    @Mock private SystemSettingsUtil.SystemSettingsReader mMockSystemSettingsReader;
    private Context mContext;
    private SystemSettingsUtil mSystemSettingsUtil;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mFeatureFlags = new FeatureFlagsImpl();

        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        // Instantiate with the mocked reader to isolate from static Settings.System calls.
        mSystemSettingsUtil = new SystemSettingsUtil(mMockSystemSettingsReader);
    }

    @Test
    @SmallTest
    @RequiresFlagsEnabled(com.android.server.telecom.flags.Flags
            .FLAG_VIBRATION_ACCOUNTS_FOR_MAIN_SETTING)
    public void testIsRingVibrationEnabled_BothSettingsOn_ReturnsTrue() throws Exception {
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.VIBRATE_ON), anyInt()))
                .thenReturn(1);
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.RING_VIBRATION_INTENSITY),
                anyInt())).thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        assertTrue(mSystemSettingsUtil.isRingVibrationEnabled(mContext, mFeatureFlags));
    }

    @Test
    @SmallTest
    @RequiresFlagsEnabled(com.android.server.telecom.flags.Flags
            .FLAG_VIBRATION_ACCOUNTS_FOR_MAIN_SETTING)
    public void testIsRingVibrationEnabled_RingVibrationOff_ReturnsFalse() {
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.VIBRATE_ON), anyInt()))
                .thenReturn(1);
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.RING_VIBRATION_INTENSITY),
                anyInt())).thenReturn(Vibrator.VIBRATION_INTENSITY_OFF);
        assertFalse(mSystemSettingsUtil.isRingVibrationEnabled(mContext, mFeatureFlags));
    }

    @Test
    @SmallTest
    @RequiresFlagsEnabled(com.android.server.telecom.flags.Flags
            .FLAG_VIBRATION_ACCOUNTS_FOR_MAIN_SETTING)
    public void testIsRingVibrationEnabled_MainVibrationOff_ReturnsFalse() {
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.VIBRATE_ON), anyInt()))
                .thenReturn(0);
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.RING_VIBRATION_INTENSITY),
                anyInt())).thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        assertFalse(mSystemSettingsUtil.isRingVibrationEnabled(mContext, mFeatureFlags));
    }


    @Test
    @SmallTest
    @RequiresFlagsEnabled(com.android.server.telecom.flags.Flags
            .FLAG_VIBRATION_ACCOUNTS_FOR_MAIN_SETTING)
    public void testIsRingVibrationEnabled_DefaultIntensityOff_ReturnsFalse() {
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.VIBRATE_ON), anyInt()))
                .thenReturn(1);
        when(mMockSystemSettingsReader.getInt(any(), eq(Settings.System.RING_VIBRATION_INTENSITY),
                anyInt())).thenReturn(Vibrator.VIBRATION_INTENSITY_OFF);
        when(mComponentContextFixture.getVibrator().getDefaultVibrationIntensity(
                VibrationAttributes.USAGE_RINGTONE))
                .thenReturn(Vibrator.VIBRATION_INTENSITY_OFF);
        assertFalse(mSystemSettingsUtil.isRingVibrationEnabled(mContext, mFeatureFlags));
    }
}