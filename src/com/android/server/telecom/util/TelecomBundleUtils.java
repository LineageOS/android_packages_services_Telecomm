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

package com.android.server.telecom.util;

import android.os.BadParcelableException;
import android.os.Bundle;
import android.telecom.Log;

/**
 * Encapsulates util methods to safely handle Bundle objects.
 */
public class TelecomBundleUtils {
    private static final String TAG = "TelecomBundleUtils";

    public static Bundle defuse(Bundle bundle) {
        if (bundle == null) {
            Log.w(TAG, "defuse: bundle is null, returning null");
            return null;
        }
        try {
            bundle.getBoolean("FOO", false);
            return bundle;
        } catch (BadParcelableException e) {
            Log.e(TAG, e, "defuse: BadParcelableException caught, returning empty Bundle.");
            return new Bundle();
        }
    }
}