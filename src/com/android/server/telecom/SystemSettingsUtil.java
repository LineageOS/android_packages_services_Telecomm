/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.telecom;

import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telecom.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Accesses the Global System settings for more control during testing.
 */
@VisibleForTesting
public class SystemSettingsUtil {

    public boolean isTheaterModeOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.THEATER_MODE_ON,
                0) == 1;
    }

    public boolean canVibrateWhenRinging(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
    }

    public boolean isEnhancedCallBlockingEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.DEBUG_ENABLE_ENHANCED_CALL_BLOCKING, 0) != 0;
    }

    public boolean setEnhancedCallBlockingEnabled(Context context, boolean enabled) {
        return Settings.System.putInt(context.getContentResolver(),
                Settings.System.DEBUG_ENABLE_ENHANCED_CALL_BLOCKING, enabled ? 1 : 0);
    }

    public boolean applyRampingRinger(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
            Settings.Global.APPLY_RAMPING_RINGER, 0) == 1;
    }

    public boolean enableRampingRingerFromDeviceConfig() {
        String enableRampingRinger = DeviceConfig.getProperty(
            DeviceConfig.Telephony.NAMESPACE,
            DeviceConfig.Telephony.RAMPING_RINGER_ENABLED);
        if (enableRampingRinger == null) {
            Log.i(this, "Telephony.RAMPING_RINGER_ENABLED is null");
            return false;
        }
        try {
            return Boolean.valueOf(enableRampingRinger);
        } catch (Exception e) {
            Log.wtf(this,
                "Error parsing Telephony.RAMPING_RINGER_ENABLED: " + e);
            return false;
        }
    }

    public int getRampingRingerDuration() {
        String rampingRingerDuration = DeviceConfig.getProperty(
            DeviceConfig.Telephony.NAMESPACE,
            DeviceConfig.Telephony.RAMPING_RINGER_DURATION);
        if (rampingRingerDuration == null) {
            Log.i(this, "Telephony.RAMPING_RINGER_DURATION is null");
            return -1;
        }
        try {
            return Integer.parseInt(rampingRingerDuration);
        } catch (Exception e) {
            Log.wtf(this,
                "Error parsing Telephony.RAMPING_RINGER_DURATION: " + e);
            return -1;
        }
    }

    public int getRampingRingerVibrationDuration() {
        String rampingRingerVibrationDuration = DeviceConfig.getProperty(
            DeviceConfig.Telephony.NAMESPACE,
            DeviceConfig.Telephony.RAMPING_RINGER_VIBRATION_DURATION);
        if (rampingRingerVibrationDuration == null) {
            Log.i(this,
                "Telephony.RAMPING_RINGER_VIBRATION_DURATION is null");
            return 0;
        }
        try {
            return Integer.parseInt(rampingRingerVibrationDuration);
        } catch (Exception e) {
            Log.wtf(this,
                "Error parsing Telephony.RAMPING_RINGER_VIBRATION_DURATION: " + e);
            return 0;
        }
    }
}

