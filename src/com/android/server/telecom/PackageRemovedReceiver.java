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
 * limitations under the License
 */
package com.android.server.telecom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.telecom.PhoneAccount;

import com.android.server.telecom.PhoneAccountRegistrar;

import android.util.Log;

public class PackageRemovedReceiver extends BroadcastReceiver {

    private PhoneAccountRegistrar mPhoneAccountRegistrar;
    private Handler mBackgroundHandler;
    private static final String TAG = "PackageRemovedReceiver";
    private UserHandleWrapper mUserHandleWrapper;

    /**
     * Default constructor required by Android.
     */
    public PackageRemovedReceiver() {
        this(null, null, new UserHandleWrapper());
    }

    public PackageRemovedReceiver(PhoneAccountRegistrar phoneAccountRegistrar,
                                  Handler backgroundHandler, UserHandleWrapper userHandleWrapper) {
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mBackgroundHandler = backgroundHandler;
        mUserHandleWrapper = userHandleWrapper;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
            Uri uri = intent.getData();
            if (uri == null) {
                Log.w(TAG, "Null URI in intent for " + action);
                return;
            }

            final String packageName = uri.getSchemeSpecificPart();
            if (packageName == null || packageName.isEmpty()) {
                Log.w(TAG, "Null or empty package name for " + action);
                return;
            }

            // Get the UID of the package that was removed.
            // This UID includes the user ID.
            final int uid = intent.getIntExtra(Intent.EXTRA_UID, Process.INVALID_UID);
            if (uid == Process.INVALID_UID) {
                Log.i(TAG, "Invalid UID for packageName - Cannot determine user.");
                return;
            }

            final UserHandle user = mUserHandleWrapper.getUserHandleForUid(uid);
            Log.i(TAG, "Package " + packageName + " (UID: " + uid + ") fully removed" +
                    " for user " + user);

            if (mBackgroundHandler != null) {
                mBackgroundHandler.post(() -> {
                    handlePackageRemovedForUserInternal(packageName, user);
                });
            } else {
                handlePackageRemovedForUserInternal(packageName, user);
            }
        }
    }

    /**
     * Handles the removal of a package for a specific user by calling upon the
     * {@link PhoneAccountRegistrar} to un-register any {@link android.telecom.PhoneAccount}s
     * associated with the package for that user.
     * (This method remains largely the same as in the previous "iterate all users" version,
     * as it already operates on a specific user).
     *
     * @param packageName The name of the removed package.
     * @param user        The UserHandle for whom to clear accounts.
     */
    private void handlePackageRemovedForUserInternal(
            String packageName,
            UserHandle user) {
        if (mPhoneAccountRegistrar != null) {
            mPhoneAccountRegistrar.clearAccounts(packageName, user);
        } else {
            Log.w(TAG, "PhoneAccountRegistrar not available to clear accounts for "
                    + packageName + " (user " + user + ")");
        }
    }


    // --- Helper methods for setting dependencies ---

    /**
     * Sets the PhoneAccountRegistrar. This is useful if you are registering the
     * BroadcastReceiver dynamically and can't use a constructor with arguments.
     * Make sure this is called before the receiver is expected to handle broadcasts
     * if not using the constructor injection.
     *
     * @param registrar The PhoneAccountRegistrar instance.
     */
    public void setPhoneAccountRegistrar(PhoneAccountRegistrar registrar) {
        this.mPhoneAccountRegistrar = registrar;
    }

    /**
     * Sets the Handler.
     *
     * @param handler The Handler instance.
     */
    public void setTelecomSystemHandler(Handler handler) {
        this.mBackgroundHandler = handler;
    }

    /**
     * Sets the UserHandleWrapper.
     *
     * @param userHandle The UserHandleWrapper instance.
     */
    public void setUserHandleWrapper(UserHandleWrapper userHandle) {
        this.mUserHandleWrapper = userHandle;
    }
}

