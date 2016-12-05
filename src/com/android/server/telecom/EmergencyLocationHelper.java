/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.telecom.Log;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Grants temporary location permission to the default dialer service during
 * emergency calls, if it doesn't already have it.
 */
@VisibleForTesting
public class EmergencyLocationHelper {
    private final Context mContext;
    private final String mDefaultDialerPackage;
    private final Timeouts.Adapter mTimeoutsAdapter;
    private UserHandle mLocationPermissionGrantedToUser;
    private long mLocationPermissionGrantedTimestampMillis;

    @VisibleForTesting
    public EmergencyLocationHelper(
            Context context,
            String defaultDialerPackage,
            Timeouts.Adapter timeoutsAdapter) {
        mContext = context;
        mDefaultDialerPackage = defaultDialerPackage;
        mTimeoutsAdapter = timeoutsAdapter;
    }

    void maybeGrantTemporaryLocationPermission(Call call, UserHandle userHandle) {
        if (shouldGrantTemporaryLocationPermission(call)) {
            grantLocationPermission(userHandle, call);
        }
    }

    void maybeRevokeTemporaryLocationPermission() {
        if (wasGrantedTemporaryLocationPermission()) {
            revokeLocationPermission();
        }
    }

    private boolean shouldGrantTemporaryLocationPermission(Call call) {
        if (call == null) {
            Log.i(this, "ShouldGrantTemporaryLocationPermission, no call");
            return false;
        }
        if (!call.isEmergencyCall() && !isInEmergencyCallbackWindow()) {
            Log.i(this, "ShouldGrantTemporaryLocationPermission, not emergency");
            return false;
        }
        if (hasLocationPermission()) {
            Log.i(this, "ShouldGrantTemporaryLocationPermission, already has location permission");
            return false;
        }
        Log.i(this, "ShouldGrantTemporaryLocationPermission, returning true");
        return true;
    }

    private void grantLocationPermission(UserHandle userHandle, Call call) {
        Log.i(this, "Granting temporary location permission to " + mDefaultDialerPackage
              + ", user: " + userHandle);
        try {
            mContext.getPackageManager().grantRuntimePermission(mDefaultDialerPackage,
                Manifest.permission.ACCESS_FINE_LOCATION, userHandle);
            recordPermissionGrant(userHandle, call.isEmergencyCall());
        } catch (Exception e) {
            Log.e(this, e, "Failed to grant location permission to " + mDefaultDialerPackage
                  + ", user: " + userHandle);
        }
    }

    private void revokeLocationPermission() {
        Log.i(this, "Revoking temporary location permission from " + mDefaultDialerPackage
              + ", user: " + mLocationPermissionGrantedToUser);
        UserHandle userHandle = mLocationPermissionGrantedToUser;
        clearPermissionGrant();
        try {
            mContext.getPackageManager().revokeRuntimePermission(mDefaultDialerPackage,
                  Manifest.permission.ACCESS_FINE_LOCATION, userHandle);
        } catch (Exception e) {
            Log.e(this, e, "Failed to revoke location permission from " + mDefaultDialerPackage
                  + ", user: " + userHandle);
        }
    }

    private boolean hasLocationPermission() {
        return mContext.getPackageManager().checkPermission(
                Manifest.permission.ACCESS_FINE_LOCATION, mDefaultDialerPackage)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void recordPermissionGrant(UserHandle userHandle, boolean startEmergencyWindow) {
        mLocationPermissionGrantedToUser = userHandle;
        if (startEmergencyWindow) {
            mLocationPermissionGrantedTimestampMillis = System.currentTimeMillis();
        }
    }

    private boolean wasGrantedTemporaryLocationPermission() {
        return mLocationPermissionGrantedToUser != null;
    }

    private void clearPermissionGrant() {
        mLocationPermissionGrantedToUser = null;
    }

    private boolean isInEmergencyCallbackWindow() {
        return System.currentTimeMillis() - mLocationPermissionGrantedTimestampMillis
                < mTimeoutsAdapter.getEmergencyCallbackWindowMillis(mContext.getContentResolver());
    }
}
