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

package com.android.server.telecom;

import static android.telecom.TelecomManager.CALL_CONNECTED_INDICATOR_NONE;
import static android.telecom.TelecomManager.CALL_CONNECTED_INDICATOR_TONE;
import static android.telecom.TelecomManager.CALL_CONNECTED_INDICATOR_VIBRATION;

import android.content.Context;
import android.content.SharedPreferences;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.flags.FeatureFlags;

@VisibleForTesting
public class CallConnectedIndicatorSettings {
    private static final String TAG = "CallConnectedIndicatorSettings";

    private static final int ALL_SUPPORTED_PREFERENCE = CALL_CONNECTED_INDICATOR_TONE
            | CALL_CONNECTED_INDICATOR_VIBRATION;
    private static final String SHARED_PREFERENCES_NAME = "call_connected_indicator_prefs";
    private static final String SHARED_PREFERENCES_KEY = "preference_key";

    private final Context mContext;
    private final FeatureFlags mFeatureFlags;
    private int mCallConnectedIndicator = CALL_CONNECTED_INDICATOR_NONE;

    public CallConnectedIndicatorSettings(Context context, FeatureFlags flag) {
        mContext = context;
        mFeatureFlags = flag;
        final SharedPreferences prefs = context.getSharedPreferences(
                SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        mCallConnectedIndicator = prefs.getInt(SHARED_PREFERENCES_KEY,
                CALL_CONNECTED_INDICATOR_NONE);
    }

    public synchronized boolean isCallConnectedVibrationEnabled() {
        return (mCallConnectedIndicator & CALL_CONNECTED_INDICATOR_VIBRATION)
                == CALL_CONNECTED_INDICATOR_VIBRATION;
    }

    public synchronized boolean isCallConnectedToneEnabled() {
        return (mCallConnectedIndicator & CALL_CONNECTED_INDICATOR_TONE)
                == CALL_CONNECTED_INDICATOR_TONE;
    }

    public synchronized void setCallConnectedIndicatorPreference(int preference) {
        if ((preference & ~ALL_SUPPORTED_PREFERENCE) > 0) {
            throw new IllegalArgumentException("Invalid preference " + preference);
        }
        mCallConnectedIndicator = preference;
        final SharedPreferences prefs = mContext.getSharedPreferences(
                SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(SHARED_PREFERENCES_KEY, mCallConnectedIndicator);
        editor.commit();
    }

    public synchronized int getCallConnectedIndicatorPreference() {
        return mCallConnectedIndicator;
    }
}
