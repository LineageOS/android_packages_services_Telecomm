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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import com.android.server.telecom.CallConnectedIndicatorSettings;
import com.android.server.telecom.flags.FeatureFlags;

import org.junit.After;
import org.junit.Before;
import org.mockito.Mock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CallConnectedIndicatorSettingsTest extends TelecomTestCase {

    private CallConnectedIndicatorSettings mCallConnectedIndicatorSettings;

    @Mock Context mMockContext;
    @Mock FeatureFlags mMockFeatureFlags;
    @Mock SharedPreferences mMockSharedPreferences;
    @Mock SharedPreferences.Editor mMockEditor;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mMockContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mMockSharedPreferences);
        when(mMockSharedPreferences.edit()).thenReturn(mMockEditor);
        when(mMockSharedPreferences.getInt(anyString(), anyInt())).thenReturn(3);
        mCallConnectedIndicatorSettings =
                new CallConnectedIndicatorSettings(mMockContext, mMockFeatureFlags);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testPreferenceBasicSettings() {
        assertEquals(3, mCallConnectedIndicatorSettings.getCallConnectedIndicatorPreference());
        assertTrue(mCallConnectedIndicatorSettings.isCallConnectedVibrationEnabled());
        assertTrue(mCallConnectedIndicatorSettings.isCallConnectedToneEnabled());

        try {
            mCallConnectedIndicatorSettings.setCallConnectedIndicatorPreference(4);
        } catch (IllegalArgumentException e) {
            assertEquals(3, mCallConnectedIndicatorSettings.getCallConnectedIndicatorPreference());
        }
        // no perferences there
        mCallConnectedIndicatorSettings.setCallConnectedIndicatorPreference(0);
        assertEquals(0, mCallConnectedIndicatorSettings.getCallConnectedIndicatorPreference());
        assertFalse(mCallConnectedIndicatorSettings.isCallConnectedVibrationEnabled());
        assertFalse(mCallConnectedIndicatorSettings.isCallConnectedToneEnabled());

        // single tone preference
        mCallConnectedIndicatorSettings.setCallConnectedIndicatorPreference(1);
        assertEquals(1, mCallConnectedIndicatorSettings.getCallConnectedIndicatorPreference());
        assertTrue(mCallConnectedIndicatorSettings.isCallConnectedToneEnabled());
        assertFalse(mCallConnectedIndicatorSettings.isCallConnectedVibrationEnabled());

        // single vibration preference
        mCallConnectedIndicatorSettings.setCallConnectedIndicatorPreference(2);
        assertEquals(2, mCallConnectedIndicatorSettings.getCallConnectedIndicatorPreference());
        assertTrue(mCallConnectedIndicatorSettings.isCallConnectedVibrationEnabled());
        assertFalse(mCallConnectedIndicatorSettings.isCallConnectedToneEnabled());

        // both tone and vibration preference
        mCallConnectedIndicatorSettings.setCallConnectedIndicatorPreference(3);
        assertEquals(3, mCallConnectedIndicatorSettings.getCallConnectedIndicatorPreference());
        assertTrue(mCallConnectedIndicatorSettings.isCallConnectedVibrationEnabled());
        assertTrue(mCallConnectedIndicatorSettings.isCallConnectedToneEnabled());
    }
}

