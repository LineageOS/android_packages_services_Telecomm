/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.telecom.callredirection;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telecom.CallRedirectionService;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.PhoneAccountRegistrar;

import java.util.List;

public class CallRedirectionProcessorHelper {

    private final Context mContext;
    private final CallsManager mCallsManager;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private String mOriginalPostDialDigits = null;

    public CallRedirectionProcessorHelper(
            Context context,
            CallsManager callsManager,
            PhoneAccountRegistrar phoneAccountRegistrar) {
        mContext = context;
        mCallsManager = callsManager;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
    }

    @VisibleForTesting
    // TODO integarte with RoleManager functions
    public ComponentName getUserDefinedCallRedirectionService() {
        String componentNameString = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.CALL_REDIRECTION_DEFAULT_APPLICATION,
                mCallsManager.getCurrentUserHandle().getIdentifier());
        if (TextUtils.isEmpty(componentNameString)) {
            Log.i(this, "Default user-defined call redirection is empty. Not performing call"
                    + " redirection.");
            return null;
        }
        return getComponentName(componentNameString,
                CallRedirectionProcessor.SERVICE_TYPE_USER_DEFINED);
    }

    @VisibleForTesting
    public ComponentName getCarrierCallRedirectionService(
            PhoneAccountHandle targetPhoneAccountHandle) {
        CarrierConfigManager configManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            Log.i(this, "Cannot get CarrierConfigManager.");
            return null;
        }
        PersistableBundle pb = configManager.getConfigForSubId(mPhoneAccountRegistrar
                .getSubscriptionIdForPhoneAccount(targetPhoneAccountHandle));
        if (pb == null) {
            Log.i(this, "Cannot get PersistableBundle.");
            return null;
        }
        String componentNameString = pb.getString(
                CarrierConfigManager.KEY_CALL_REDIRECTION_SERVICE_COMPONENT_NAME_STRING);
        if (componentNameString == null) {
            Log.i(this, "Cannot get carrier componentNameString.");
            return null;
        }
        return getComponentName(componentNameString,
                CallRedirectionProcessor.SERVICE_TYPE_CARRIER);
    }

    protected ComponentName getComponentName(String componentNameString, String serviceType) {
        ComponentName componentName = ComponentName.unflattenFromString(componentNameString);
        if (componentName == null) {
            Log.w(this, "ComponentName is null from string: " + componentNameString);
            return null;
        }
        Intent intent = new Intent(CallRedirectionService.SERVICE_INTERFACE);
        intent.setComponent(componentName);
        List<ResolveInfo> entries = mContext.getPackageManager().queryIntentServicesAsUser(
                intent, 0, mCallsManager.getCurrentUserHandle().getIdentifier());
        if (entries.isEmpty()) {
            Log.i(this, "There are no " + serviceType + " call redirection services installed" +
                    " on this device.");
            return null;
        } else if (entries.size() != 1) {
            Log.i(this, "There are multiple " + serviceType + " call redirection services" +
                    " installed on this device.");
            return null;
        }
        ResolveInfo entry = entries.get(0);
        if (entry.serviceInfo == null) {
            Log.w(this, "The " + serviceType + " call redirection service has invalid" +
                    " service info");
            return null;
        }
        if (entry.serviceInfo.permission == null || !entry.serviceInfo.permission.equals(
                Manifest.permission.BIND_CALL_REDIRECTION_SERVICE)) {
            Log.w(this, "CallRedirectionService must require BIND_CALL_REDIRECTION_SERVICE"
                    + " permission: " + entry.serviceInfo.packageName);
            return null;
        }
        AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        if (appOps.noteOpNoThrow(AppOpsManager.OP_PROCESS_OUTGOING_CALLS, Binder.getCallingUid(),
                entry.serviceInfo.packageName) != AppOpsManager.MODE_ALLOWED) {
            Log.w(this, "App Ops does not allow " + entry.serviceInfo.packageName);
            return null;
        }
        return componentName;
    }

    /**
     * Format Number to E164, and remove post dial digits.
     */
    protected Uri formatNumberForRedirection(Uri handle) {
        return removePostDialDigits(formatNumberToE164(handle));
    }

    protected Uri processNumberWhenRedirectionComplete(Uri handle) {
        return appendStoredPostDialDigits(formatNumberForRedirection(handle));
    }

    protected void storePostDialDigits(Uri handle) {
        String number = handle.getSchemeSpecificPart();
        mOriginalPostDialDigits += PhoneNumberUtils.extractPostDialPortion(number);
        Log.i(this, "storePostDialDigits, stored post dial digits: "
                + Log.pii(mOriginalPostDialDigits));
    }

    protected Uri appendStoredPostDialDigits(Uri handle) {
        String number = handle.getSchemeSpecificPart();
        number += mOriginalPostDialDigits;
        Log.i(this, "appendStoredPostDialDigits, appended number: " + Log.pii(number));
        return Uri.fromParts(handle.getScheme(), number, null);
    }

    protected Uri formatNumberToE164(Uri handle) {
        String number = handle.getSchemeSpecificPart();

        // Format number to E164
        TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        Log.i(this, "formatNumberToE164, original number: " + Log.pii(number));
        number = PhoneNumberUtils.formatNumberToE164(number, tm.getNetworkCountryIso());
        Log.i(this, "formatNumberToE164, formatted E164 number: " + Log.pii(number));
        // if there is a problem with parsing the phone number, formatNumberToE164 will return null;
        // and should just use the original number in that case.
        if (number == null) {
            return handle;
        } else {
            return Uri.fromParts(handle.getScheme(), number, null);
        }
    }

    protected Uri removePostDialDigits(Uri handle) {
        String number = handle.getSchemeSpecificPart();

        // Extract the post dial portion
        number = PhoneNumberUtils.extractNetworkPortionAlt(number);
        Log.i(this, "removePostDialDigits, number after being extracted post dial digits: "
                + Log.pii(number));
        // if there is a problem with parsing the phone number, removePostDialDigits will return
        // null; and should just use the original number in that case.
        if (number == null) {
            return handle;
        } else {
            return Uri.fromParts(handle.getScheme(), number, null);
        }
    }

}
