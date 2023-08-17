/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.telecom.Log;

import com.android.server.telecom.components.ErrorDialogActivity;

public final class UserUtil {

    private UserUtil() {
    }

    private static UserInfo getUserInfoFromUserHandle(Context context, UserHandle userHandle) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return userManager.getUserInfo(userHandle.getIdentifier());
    }

    public static boolean isManagedProfile(Context context, UserHandle userHandle) {
        UserInfo userInfo = getUserInfoFromUserHandle(context, userHandle);
        return userInfo != null && userInfo.isManagedProfile();
    }

    public static boolean isProfile(Context context, UserHandle userHandle) {
        UserInfo userInfo = getUserInfoFromUserHandle(context, userHandle);
        return userInfo != null && userInfo.profileGroupId != userInfo.id;
    }

    public static void showErrorDialogForRestrictedOutgoingCall(Context context,
            int stringId, String tag, String reason) {
        final Intent intent = new Intent(context, ErrorDialogActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ErrorDialogActivity.ERROR_MESSAGE_ID_EXTRA, stringId);
        context.startActivityAsUser(intent, UserHandle.CURRENT);
        Log.w(tag, "Rejecting non-emergency phone call because "
                + reason);
    }

    public static boolean hasOutgoingCallsUserRestriction(Context context,
            UserHandle userHandle, Uri handle, boolean isSelfManaged, String tag) {
        // Set handle for conference calls. Refer to {@link Connection#ADHOC_CONFERENCE_ADDRESS}.
        if (handle == null) {
            handle = Uri.parse("tel:conf-factory");
        }

        if(!isSelfManaged) {
            // Check DISALLOW_OUTGOING_CALLS restriction. Note: We are skipping this
            // check in a managed profile user because this check can always be bypassed
            // by copying and pasting the phone number into the personal dialer.
            if (!UserUtil.isManagedProfile(context, userHandle)) {
                // Only emergency calls are allowed for users with the DISALLOW_OUTGOING_CALLS
                // restriction.
                if (!TelephonyUtil.shouldProcessAsEmergency(context, handle)) {
                    final UserManager userManager =
                            (UserManager) context.getSystemService(Context.USER_SERVICE);
                    if (userManager.hasBaseUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS,
                            userHandle)) {
                        String reason = "of DISALLOW_OUTGOING_CALLS restriction";
                        showErrorDialogForRestrictedOutgoingCall(context,
                                R.string.outgoing_call_not_allowed_user_restriction, tag, reason);
                        return true;
                    } else if (userManager.hasUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS,
                            userHandle)) {
                        final DevicePolicyManager dpm =
                                context.getSystemService(DevicePolicyManager.class);
                        if (dpm == null) {
                            return true;
                        }
                        final Intent adminSupportIntent = dpm.createAdminSupportIntent(
                                UserManager.DISALLOW_OUTGOING_CALLS);
                        if (adminSupportIntent != null) {
                            context.startActivityAsUser(adminSupportIntent, userHandle);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
