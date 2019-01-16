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

package com.android.server.telecom;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.provider.CallLog;
import android.telecom.CallScreeningService;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;

import java.util.Arrays;

/**
 * Responsible for handling reports via
 * {@link android.telecom.TelecomManager#reportNuisanceCallStatus(Uri, boolean)} as to whether the
 * user has indicated a call is a nuisance call.
 *
 * Since nuisance reports can be initiated from the call log, potentially long after a call has
 * completed, calls are identified by the {@link Call#getHandle()}.  A nuisance report for a call is
 * only accepted if:
 * <ul>
 *     <li>A missed, incoming, or rejected call to that number exists in the call log.  We want to
 *     avoid a scenario where a user reports a single outgoing call as a nuisance call.</li>
 *     <li>The call occurred via a sim-based phone account; we do not want to report nuisance calls
 *     which only ever took place via a self-managed ConnectionService.  It is, however, valid for
 *     a number to be contacted both via a sim-based phone account and a self-managed one.</li>
 *     <li>The {@link CallScreeningService} has provided call identification for calls in the past.
 *     This provides an incentive for {@link CallScreeningService} implementations to use the caller
 *     ID APIs appropriately if they are going to benefit from use reports of nuisance and
 *     non-nuisance calls.</li>
 * </ul>
 */
public class NuisanceCallReporter {
    /**
     * Columns we want to retrieve from the call log.
     */
    private static final String[] CALL_LOG_PROJECTION = new String[] {
            CallLog.Calls._ID,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
            CallLog.Calls.PHONE_ACCOUNT_ID
    };

    public static final int CALL_LOG_COLUMN_ID =
            Arrays.asList(CALL_LOG_PROJECTION).indexOf(CallLog.Calls._ID);
    public static final int CALL_LOG_COLUMN_DURATION =
            Arrays.asList(CALL_LOG_PROJECTION).indexOf(CallLog.Calls.DURATION);
    public static final int CALL_LOG_COLUMN_TYPE =
            Arrays.asList(CALL_LOG_PROJECTION).indexOf(CallLog.Calls.TYPE);
    public static final int CALL_LOG_COLUMN_PHONE_ACCOUNT_COMPONENT_NAME =
            Arrays.asList(CALL_LOG_PROJECTION).indexOf(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME);
    public static final int CALL_LOG_COLUMN_PHONE_ACCOUNT_ID =
            Arrays.asList(CALL_LOG_PROJECTION).indexOf(CallLog.Calls.PHONE_ACCOUNT_ID);

    /**
     * Represents information about a nuisance report via
     * {@link android.telecom.TelecomManager#reportNuisanceCallStatus(Uri, boolean)}.
     */
    private static class NuisanceReport {
        public String callScreeningPackageName;
        public Uri handle;
        public boolean isNuisance;
        public NuisanceReport(String packageName, Uri handle, boolean isNuisance) {
            this.callScreeningPackageName = packageName;
            this.handle = handle;
            this.isNuisance = isNuisance;
        }
    }

    /**
     * Proxy interface to abstract calls to
     * {@link android.telephony.PhoneNumberUtils#formatNumberToE164(String, String)}.
     * Facilitates testing.
     */
    public interface PhoneNumberUtilsProxy {
        String formatNumberToE164(String number);
    }

    /**
     * Proxy interface to abstract queries to the package manager to determine if a
     * {@link PhoneAccountHandle} is for a self-managed connection service.
     */
    public interface PhoneAccountRegistrarProxy {
        boolean isSelfManagedConnectionService(PhoneAccountHandle handle);
    }

    /**
     * Restrict to call log entries for the specified number where its an incoming, missed, blocked
     * or rejected call.
     */
    private static final String NUMBER_WHERE_CLAUSE =
            CallLog.Calls.CACHED_NORMALIZED_NUMBER + " = ? AND " + CallLog.Calls.TYPE
                    + " IN (" + CallLog.Calls.INCOMING_TYPE + "," + CallLog.Calls.MISSED_TYPE + ","
                    + CallLog.Calls.BLOCKED_TYPE + "," + CallLog.Calls.REJECTED_TYPE + ")";

    /**
     * Call log where clause to find entries with call identification reported by a specified call
     * screening service.
     */
    private static final String CALL_ID_PACKAGE_WHERE_CLAUSE =
            CallLog.Calls.CALL_ID_PACKAGE_NAME + " = ? ";

    private final Context mContext;
    private final PhoneNumberUtilsProxy mPhoneNumberUtilsProxy;
    private final PhoneAccountRegistrarProxy mPhoneAccountRegistrarProxy;
    private UserHandle mCurrentUserHandle;

    public NuisanceCallReporter(Context context, PhoneNumberUtilsProxy phoneNumberUtilsProxy,
            PhoneAccountRegistrarProxy phoneAccountRegistrarProxy) {
        mContext = context;
        mPhoneNumberUtilsProxy = phoneNumberUtilsProxy;
        mPhoneAccountRegistrarProxy = phoneAccountRegistrarProxy;
    }

    public void setCurrentUserHandle(UserHandle userHandle) {
        if (userHandle == null) {
            Log.d(this, "setCurrentUserHandle, userHandle = null");
            userHandle = Process.myUserHandle();
        }
        Log.d(this, "setCurrentUserHandle, %s", userHandle);
        mCurrentUserHandle = userHandle;
    }

    /**
     * Given a call handle reported by the default dialer, inform the
     * {@link android.telecom.CallScreeningService} of whether the user has indicated a call is
     * or is not a nuisance call.
     *
     * @param callScreeningPackageName the package name of the call screening service.
     * @param handle the handle of the call to report nuisance status on.
     * @param isNuisance {@code true} if the call is a nuisance call, {@code false} otherwise.
     */
    public void reportNuisanceCallStatus(@NonNull String callScreeningPackageName,
            @NonNull Uri handle, boolean isNuisance) {

        // Don't report the nuisance status to a call screening app if it has not provided any
        // caller id info in the past.
        if (!hasCallScreeningServiceProvidedCallId(callScreeningPackageName)) {
            Log.i(this, "reportNuisanceCallStatus: app %s has not provided caller ID; skipping.",
                    callScreeningPackageName);
            return;
        }

        maybeSendNuisanceReport(new NuisanceReport(callScreeningPackageName, handle, isNuisance));
    }

    private void maybeSendNuisanceReport(@NonNull NuisanceReport nuisanceReport) {
        Uri callsUri = CallLog.Calls.CONTENT_URI;
        if (mCurrentUserHandle == null) {
            return;
        }

        ContentProvider.maybeAddUserId(CallLog.Calls.CONTENT_URI,
                mCurrentUserHandle.getIdentifier());

        String normalizedNumber = mPhoneNumberUtilsProxy.formatNumberToE164(
                nuisanceReport.handle.getSchemeSpecificPart());

        // Query the call log for the most recent information about this call.
        Cursor cursor = mContext.getContentResolver().query(callsUri, CALL_LOG_PROJECTION,
                NUMBER_WHERE_CLAUSE, new String[] { normalizedNumber },
                CallLog.Calls.DEFAULT_SORT_ORDER);
        Log.d(this, "maybeSendNuisanceReport:  number=%s, isNuisance=%b",
                Log.piiHandle(normalizedNumber), nuisanceReport.isNuisance);
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    final long duration = cursor.getLong(CALL_LOG_COLUMN_DURATION);
                    final int callType = cursor.getInt(CALL_LOG_COLUMN_TYPE);
                    final String phoneAccountComponentName = cursor.getString(
                            CALL_LOG_COLUMN_PHONE_ACCOUNT_COMPONENT_NAME);
                    final String phoneAccountId = cursor.getString(
                            CALL_LOG_COLUMN_PHONE_ACCOUNT_ID);

                    PhoneAccountHandle handle = new PhoneAccountHandle(
                            ComponentName.unflattenFromString(phoneAccountComponentName),
                            phoneAccountId);

                    if (mPhoneAccountRegistrarProxy.isSelfManagedConnectionService(handle)) {
                        // Skip this call log entry; it was made via a self-managed CS.
                        Log.d(this, "maybeSendNuisanceReport: skip self-mgd CS %s",
                                phoneAccountComponentName);
                        continue;
                    }

                    sendNuisanceReportIntent(nuisanceReport, duration, callType);
                    // Stop when we send a nuisance report.
                    break;
                }
            } finally {
                cursor.close();
            }
        }
    }

    /**
     * Determines if a {@link CallScreeningService} has provided
     * {@link android.telecom.CallIdentification} for calls in the past.
     * @param packageName The package name of the {@link CallScreeningService}.
     * @return {@code true} if the app has provided call identification, {@code false} otherwise.
     */
    private boolean hasCallScreeningServiceProvidedCallId(@NonNull String packageName) {
        // Query the call log for any entries which have call identification provided by the call
        // screening package.
        Cursor cursor = mContext.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                CALL_LOG_PROJECTION, CALL_ID_PACKAGE_WHERE_CLAUSE, new String[] { packageName },
                CallLog.Calls.DEFAULT_SORT_ORDER + " LIMIT 1");

        return cursor.getCount() > 0;
    }

    private void sendNuisanceReportIntent(@NonNull NuisanceReport nuisanceReport, long duration,
            int callType) {
        Log.i(this, "handleCallLogResult: number=%s, duration=%d, type=%d",
                Log.piiHandle(nuisanceReport.handle), duration, callType);

        Intent intent = new Intent(CallScreeningService.ACTION_NUISANCE_CALL_STATUS_CHANGED);
        intent.setPackage(nuisanceReport.callScreeningPackageName);
        intent.putExtra(CallScreeningService.EXTRA_CALL_HANDLE, nuisanceReport.handle);
        intent.putExtra(CallScreeningService.EXTRA_IS_NUISANCE, nuisanceReport.isNuisance);
        intent.putExtra(CallScreeningService.EXTRA_CALL_TYPE, callType);
        intent.putExtra(CallScreeningService.EXTRA_CALL_DURATION, getCallDurationBucket(duration));
        mContext.sendBroadcastAsUser(intent, mCurrentUserHandle);
    }

    /**
     * Maps a call duration in milliseconds to a call duration bucket.
     * @param callDuration Call duration, in milliseconds.
     * @return The call duration bucket
     */
    public @CallScreeningService.CallDuration int getCallDurationBucket(long callDuration) {
        if (callDuration < 3000L) {
            return CallScreeningService.CALL_DURATION_VERY_SHORT;
        } else if (callDuration >= 3000L && callDuration < 60000L) {
            return CallScreeningService.CALL_DURATION_SHORT;
        } else if (callDuration >= 6000L && callDuration < 120000L) {
            return CallScreeningService.CALL_DURATION_MEDIUM;
        } else {
            return CallScreeningService.CALL_DURATION_LONG;
        }
    }
}
