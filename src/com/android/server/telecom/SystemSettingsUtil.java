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

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.provider.DeviceConfig;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.flags.FeatureFlags;

/**
 * Accesses the Global System settings for more control during testing.
 */
@VisibleForTesting
public class SystemSettingsUtil {
    /**
     * Abstracts away the static {@link Settings.System} calls so they can be mocked in tests.
     */
    @VisibleForTesting
    public interface SystemSettingsReader {
        int getInt(ContentResolver cr, String name, int def);
        int getIntForUser(ContentResolver cr, String name, int def, int userHandle);
    }

    private static class DefaultSystemSettingsReader implements SystemSettingsReader {
        @Override
        public int getInt(ContentResolver cr, String name, int def) {
            return Settings.System.getInt(cr, name, def);
        }

        @Override
        public int getIntForUser(ContentResolver cr, String name, int def, int userHandle) {
            return Settings.System.getIntForUser(cr, name, def, userHandle);
        }
    }

    private final SystemSettingsReader mSystemSettingsReader;

    public SystemSettingsUtil() {
        this(new DefaultSystemSettingsReader());
    }

    @VisibleForTesting
    public SystemSettingsUtil(SystemSettingsReader systemSettingsReader) {
        mSystemSettingsReader = systemSettingsReader;
    }

    /** Flag for whether or not to support audio coupled haptics in ramping ringer. */
    private static final String RAMPING_RINGER_AUDIO_COUPLED_VIBRATION_ENABLED =
            "ramping_ringer_audio_coupled_vibration_enabled";


    /**
     * Determines if the primary "vibration" toggle is on in the system settings (see Settings -->
     * Sound and Vibration --> Vibration and Haptics --> Use Vibration and Haptics).
     * @param context the context to use for checking the settings.
     * @return {@code true} if the primary haptic toggle is on, {@code false} otherwise.
     */
    private boolean isVibrationEnabled(Context context, FeatureFlags flags) {
        if (!flags.vibrationAccountsForMainSetting()) {
            return true;
        }
        // Note, there is no constant for the on/off.  0 is used elsewhere in the platform when this
        // key is referenced.
        return mSystemSettingsReader.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_ON, 1) != 0;
    }

    public boolean isRingVibrationEnabled(Context context, FeatureFlags flags) {
        // Ramping ringer should only be applied when ring vibration is ON, otherwise the
        // ringtone sound should not be delayed as there will be no ring vibration.
        if (flags.resolveHiddenDependenciesTwo()) {
            // Note: VIBRATE_WHEN_RINGING is deprecated but is currently the only system API that
            // the Haptics Framework team provides. For mainlining Telecom, using
            // VIBRATE_WHEN_RINGING is our only option currently until the request for a new system
            // API (b/441480678) has been met.
            return mSystemSettingsReader.getInt(context.getContentResolver(),
                    Settings.System.VIBRATE_WHEN_RINGING,
                    context.getSystemService(Vibrator.class).getDefaultVibrationIntensity(
                            VibrationAttributes.USAGE_RINGTONE))
                    != 0 && isVibrationEnabled(context, flags);
        } else {
            // Note: ring_vibration_intensity is documented to have values 0, 1, 2, 3, where 0 is
            // no intensity.  Elsewhere in the platform 0 is used directly assuming this is just a
            // typical integer intensity in range [0,3].
            return mSystemSettingsReader.getIntForUser(context.getContentResolver(),
                    Settings.System.RING_VIBRATION_INTENSITY,
                    context.getSystemService(Vibrator.class).getDefaultVibrationIntensity(
                            VibrationAttributes.USAGE_RINGTONE),
                    UserUtil.getUserIdFromContext(context, flags))
                    != Vibrator.VIBRATION_INTENSITY_OFF && isVibrationEnabled(context, flags);
        }
    }

    public boolean isRampingRingerEnabled(Context context) {
        return context.getSystemService(AudioManager.class).isRampingRingerEnabled();
    }

    public boolean isAudioCoupledVibrationForRampingRingerEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TELEPHONY,
                RAMPING_RINGER_AUDIO_COUPLED_VIBRATION_ENABLED, false);
    }

    public boolean isHapticPlaybackSupported(Context context) {
        return context.getSystemService(AudioManager.class).isHapticPlaybackSupported();
    }
}

