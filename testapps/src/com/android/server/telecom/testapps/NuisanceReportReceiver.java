/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.telecom.testapps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telecom.CallScreeningService;
import android.widget.Toast;

/**
 * Example receiver for nuisance call reports from Telecom.
 */
public class NuisanceReportReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(CallScreeningService.ACTION_NUISANCE_CALL_STATUS_CHANGED)) {
            Uri handle = intent.getParcelableExtra(CallScreeningService.EXTRA_CALL_HANDLE);
            boolean isNuisance = intent.getBooleanExtra(CallScreeningService.EXTRA_IS_NUISANCE,
                    false);
            int durationBucket = intent.getIntExtra(CallScreeningService.EXTRA_CALL_DURATION, 0);
            int callType = intent.getIntExtra(CallScreeningService.EXTRA_CALL_TYPE, 0);
            handleNuisanceReport(context, handle, isNuisance, durationBucket, callType);
        }
    }

    private void handleNuisanceReport(Context context, Uri handle, boolean isNuisance,
            int durationBucket, int callType) {

        String message = "Nuisance report for: " + handle + " isNuisance=" + isNuisance
                + " duration=" + durationBucket + " callType=" + callType;
        Toast.makeText(context,
                (CharSequence) message,
                Toast.LENGTH_LONG)
                .show();
    }
}
