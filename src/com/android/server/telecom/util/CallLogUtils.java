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

import static android.provider.CallLog.Calls.ADD_FOR_ALL_USERS;
import static android.provider.CallLog.Calls.ASSERTED_DISPLAY_NAME;
import static android.provider.CallLog.Calls.BLOCK_REASON;
import static android.provider.CallLog.Calls.CACHED_NAME;
import static android.provider.CallLog.Calls.CALL_SCREENING_APP_NAME;
import static android.provider.CallLog.Calls.CALL_SCREENING_COMPONENT_NAME;
import static android.provider.CallLog.Calls.COMPOSER_PHOTO_URI;
import static android.provider.CallLog.Calls.CONTENT_URI;
import static android.provider.CallLog.Calls.DATA_USAGE;
import static android.provider.CallLog.Calls.DATE;
import static android.provider.CallLog.Calls.DEFAULT_SORT_ORDER;
import static android.provider.CallLog.Calls.DURATION;
import static android.provider.CallLog.Calls.FEATURES;
import static android.provider.CallLog.Calls.IS_BUSINESS_CALL;
import static android.provider.CallLog.Calls.IS_PHONE_ACCOUNT_MIGRATION_PENDING;
import static android.provider.CallLog.Calls.IS_READ;
import static android.provider.CallLog.Calls.MISSED_REASON;
import static android.provider.CallLog.Calls.MISSED_TYPE;
import static android.provider.CallLog.Calls.NEW;
import static android.provider.CallLog.Calls.NUMBER;
import static android.provider.CallLog.Calls.NUMBER_PRESENTATION;
import static android.provider.CallLog.Calls.PHONE_ACCOUNT_ADDRESS;
import static android.provider.CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME;
import static android.provider.CallLog.Calls.PHONE_ACCOUNT_ID;
import static android.provider.CallLog.Calls.POST_DIAL_DIGITS;
import static android.provider.CallLog.Calls.PRESENTATION_ALLOWED;
import static android.provider.CallLog.Calls.PRESENTATION_UNAVAILABLE;
import static android.provider.CallLog.Calls.PRESENTATION_UNKNOWN;
import static android.provider.CallLog.Calls.PRIORITY;
import static android.provider.CallLog.Calls.SUBJECT;
import static android.provider.CallLog.Calls.TYPE;
import static android.provider.CallLog.Calls.UUID;
import static android.provider.CallLog.Calls.VIA_NUMBER;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.location.Country;
import android.location.CountryDetector;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.provider.ContactsContract.CommonDataKinds.Callable;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.DataUsageFeedback;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.telecom.flags.Flags;

import java.util.List;
import java.util.Locale;

/**
 * Encapsulates the util methods to update the call log
 */
public class CallLogUtils {

    private static final String LOG_TAG = "CallLogUtils";
    private static final boolean VERBOSE_LOG = false; // DON'T SUBMIT WITH TRUE.
    /**
     * If a successful call is made that is longer than this duration, update the phone number in
     * the ContactsProvider with the normalized version of the number, based on the user's current
     * country code.
     */
    private static final int MIN_DURATION_FOR_NORMALIZED_NUMBER_UPDATE_MS = 1000 * 10;
    /**
     * The default maximum number of call log entries stored in the call log provider for each
     * {@link PhoneAccountHandle}.
     */
    private static final int DEFAULT_MAX_CALL_LOG_SIZE = 500;
    /**
     * Expected component name of Telephony phone accounts.
     */
    private static final ComponentName TELEPHONY_COMPONENT_NAME =
        new ComponentName("com.android.phone",
            "com.android.services.telephony.TelephonyConnectionService");

    /**
     * The "shadow" provider stores calllog when the real calllog provider is encrypted.  The real
     * provider will alter copy from it when it starts, and remove the entries in the shadow.
     *
     * <p>See the comment in {@link Calls#addCall} for the details.
     * TODO: make them as System API in CallLog?
     */
    private static final String SHADOW_AUTHORITY = "call_log_shadow";
    private static final Uri SHADOW_CONTENT_URI = Uri.parse("content://call_log_shadow/calls");

    /**
     * Adds a call to the call log.
     *
     * @param ci the CallerInfo object to get the target contact from.  Can be null if the
     *     contact is unknown.
     * @param context the context used to get the ContentResolver
     * @param number the phone number to be added to the calls db
     * @param presentation enum value from TelecomManager.PRESENTATION_xxx, which is set by the
     *     network and denotes the number presenting rules for "allowed", "payphone", "restricted"
     *     or "unknown"
     * @param callType enumerated values for "incoming", "outgoing", or "missed"
     * @param features features of the call (e.g. Video).
     * @param accountHandle The accountHandle object identifying the provider of the call
     * @param start time stamp for the call in milliseconds
     * @param duration call duration in seconds
     * @param dataUsage data usage for the call in bytes, null if data usage was not tracked for
     *     the call.
     * @param isPhoneAccountMigrationPending whether the PhoneAccountHandle ID need to migrate
     * @return The URI of the call log entry belonging to the user that made or received this
     *     call.
     */
    public static Uri addCall(CallerInfo ci, Context context, String number,
        int presentation, int callType, int features,
        PhoneAccountHandle accountHandle,
        long start, int duration, Long dataUsage, long missedReason,
        int isPhoneAccountMigrationPending) {
        return addCall(ci, context, number, "" /* postDialDigits */, "" /* viaNumber */,
            presentation, callType, features, accountHandle, start, duration,
            dataUsage, false /* addForAllUsers */, null /* userToBeInsertedTo */,
            false /* isRead */, CallLog.Calls.BLOCK_REASON_NOT_BLOCKED /* callBlockReason */,
            null /* callScreeningAppName */, null /* callScreeningComponentName */,
            missedReason, isPhoneAccountMigrationPending);
    }

    /**
     * Adds a call to the call log.
     *
     * @param ci the CallerInfo object to get the target contact from.  Can be null if the
     *     contact is unknown.
     * @param context the context used to get the ContentResolver
     * @param number the phone number to be added to the calls db
     * @param viaNumber the secondary number that the incoming call received with. If the call
     *     was received with the SIM assigned number, then this field must be ''.
     * @param presentation enum value from TelecomManager.PRESENTATION_xxx, which is set by the
     *     network and denotes the number presenting rules for "allowed", "payphone", "restricted"
     *     or "unknown"
     * @param callType enumerated values for "incoming", "outgoing", or "missed"
     * @param features features of the call (e.g. Video).
     * @param accountHandle The accountHandle object identifying the provider of the call
     * @param start time stamp for the call in milliseconds
     * @param duration call duration in seconds
     * @param dataUsage data usage for the call in bytes, null if data usage was not tracked for
     *     the call.
     * @param addForAllUsers If true, the call is added to the call log of all currently running
     *     users. The caller must have the MANAGE_USERS permission if this is true.
     * @param userToBeInsertedTo {@link UserHandle} of user that the call is going to be
     *     inserted to. null if it is inserted to the current user. The value is ignored if
     *     addForAllUsers is true.
     * @param isPhoneAccountMigrationPending whether the PhoneAccountHandle ID need to migrate
     * @return The URI of the call log entry belonging to the user that made or received this
     *     call.
     */
    public static Uri addCall(CallerInfo ci, Context context, String number,
        String postDialDigits, String viaNumber, int presentation, int callType,
        int features, PhoneAccountHandle accountHandle, long start, int duration,
        Long dataUsage, boolean addForAllUsers, UserHandle userToBeInsertedTo,
        long missedReason, int isPhoneAccountMigrationPending) {
        return addCall(ci, context, number, postDialDigits, viaNumber, presentation, callType,
            features, accountHandle, start, duration, dataUsage, addForAllUsers,
            userToBeInsertedTo, false /* isRead */, CallLog.Calls.BLOCK_REASON_NOT_BLOCKED
            /* callBlockReason */, null /* callScreeningAppName */,
            null /* callScreeningComponentName */, missedReason,
            isPhoneAccountMigrationPending);
    }

    /**
     * Adds a call to the call log.
     *
     * @param ci the CallerInfo object to get the target contact from.  Can be null if the
     *     contact is unknown.
     * @param context the context used to get the ContentResolver
     * @param number the phone number to be added to the calls db
     * @param postDialDigits the post-dial digits that were dialed after the number, if it was
     *     outgoing. Otherwise it is ''.
     * @param viaNumber the secondary number that the incoming call received with. If the call
     *     was received with the SIM assigned number, then this field must be ''.
     * @param presentation enum value from TelecomManager.PRESENTATION_xxx, which is set by the
     *     network and denotes the number presenting rules for "allowed", "payphone", "restricted"
     *     or "unknown"
     * @param callType enumerated values for "incoming", "outgoing", or "missed"
     * @param features features of the call (e.g. Video).
     * @param accountHandle The accountHandle object identifying the provider of the call
     * @param start time stamp for the call in milliseconds
     * @param duration call duration in seconds
     * @param dataUsage data usage for the call in bytes, null if data usage was not tracked for
     *     the call.
     * @param addForAllUsers If true, the call is added to the call log of all currently running
     *     users. The caller must have the MANAGE_USERS permission if this is true.
     * @param userToBeInsertedTo {@link UserHandle} of user that the call is going to be
     *     inserted to. null if it is inserted to the current user. The value is ignored if
     *     addForAllUsers is true.
     * @param isRead Flag to show if the missed call log has been read by the user or not. Used
     *     for call log restore of missed calls.
     * @param callBlockReason The reason why the call is blocked.
     * @param callScreeningAppName The call screening application name which block the call.
     * @param callScreeningComponentName The call screening component name which block the
     *     call.
     * @param missedReason The encoded missed information of the call.
     * @param isPhoneAccountMigrationPending whether the PhoneAccountHandle ID need to migrate
     * @return The URI of the call log entry belonging to the user that made or received this
     *     call.  This could be of the shadow provider.  Do not return it to non-system apps, as
     *     they don't have permissions.
     */
    public static Uri addCall(CallerInfo ci, Context context, String number,
        String postDialDigits, String viaNumber, int presentation, int callType,
        int features, PhoneAccountHandle accountHandle, long start, int duration,
        Long dataUsage, boolean addForAllUsers, UserHandle userToBeInsertedTo,
        boolean isRead, int callBlockReason, CharSequence callScreeningAppName,
        String callScreeningComponentName, long missedReason,
        int isPhoneAccountMigrationPending) {
        AddCallParams.AddCallParametersBuilder builder =
            new AddCallParams.AddCallParametersBuilder();
        builder.setCallerInfo(ci);
        builder.setNumber(number);
        builder.setPostDialDigits(postDialDigits);
        builder.setViaNumber(viaNumber);
        builder.setPresentation(presentation);
        builder.setCallType(callType);
        builder.setFeatures(features);
        builder.setAccountHandle(accountHandle);
        builder.setStart(start);
        builder.setDuration(duration);
        builder.setDataUsage(dataUsage == null ? Long.MIN_VALUE : dataUsage);
        builder.setAddForAllUsers(addForAllUsers);
        builder.setUserToBeInsertedTo(userToBeInsertedTo);
        builder.setIsRead(isRead);
        builder.setCallBlockReason(callBlockReason);
        builder.setCallScreeningAppName(callScreeningAppName);
        builder.setCallScreeningComponentName(callScreeningComponentName);
        builder.setMissedReason(missedReason);
        builder.setIsPhoneAccountMigrationPending(isPhoneAccountMigrationPending);

        return addCall(context, builder.build());
    }

    /**
     * Adds a call to the call log, using the provided parameters
     *
     * @return The URI of the call log entry belonging to the user that made or received this
     *     call.  This could be of the shadow provider.  Do not return it to non-system apps, as
     *     they don't have permissions.
     */
    public static @NonNull Uri addCall(
        @NonNull Context context, @NonNull AddCallParams params) {
        if (VERBOSE_LOG) {
            Log.v(LOG_TAG, String.format("Add call: number=%s, user=%s, for all=%s",
                params.mNumber, params.mUserToBeInsertedTo, params.mAddForAllUsers));
        }
        final ContentResolver resolver = context.getContentResolver();

        String accountAddress = getLogAccountAddress(context, params.mAccountHandle);

        int numberPresentation = getLogNumberPresentation(params.mNumber, params.mPresentation);
        String name = (params.mCallerInfo != null) ? params.mCallerInfo.getName() : "";
        if (numberPresentation != PRESENTATION_ALLOWED) {
            params.mNumber = "";
            if (params.mCallerInfo != null) {
                name = "";
            }
        }

        // accountHandle information
        String accountComponentString = null;
        String accountId = null;
        if (params.mAccountHandle != null) {
            accountComponentString = params.mAccountHandle.getComponentName().flattenToString();
            accountId = params.mAccountHandle.getId();
        }

        ContentValues values = new ContentValues(14);
        values.put(NUMBER, params.mNumber);
        values.put(POST_DIAL_DIGITS, params.mPostDialDigits);
        values.put(VIA_NUMBER, params.mViaNumber);
        values.put(NUMBER_PRESENTATION, Integer.valueOf(numberPresentation));
        values.put(TYPE, Integer.valueOf(params.mCallType));
        values.put(FEATURES, params.mFeatures);
        values.put(DATE, Long.valueOf(params.mStart));
        values.put(DURATION, Long.valueOf(params.mDuration));
        if (params.mDataUsage != Long.MIN_VALUE) {
            values.put(DATA_USAGE, params.mDataUsage);
        }
        values.put(PHONE_ACCOUNT_COMPONENT_NAME, accountComponentString);
        values.put(PHONE_ACCOUNT_ID, accountId);
        values.put(PHONE_ACCOUNT_ADDRESS, accountAddress);
        values.put(NEW, Integer.valueOf(1));
        values.put(CACHED_NAME, name);
        values.put(ADD_FOR_ALL_USERS, params.mAddForAllUsers ? 1 : 0);

        if (params.mCallType == MISSED_TYPE) {
            values.put(IS_READ, Integer.valueOf(params.mIsRead ? 1 : 0));
        }

        values.put(BLOCK_REASON, params.mCallBlockReason);
        values.put(CALL_SCREENING_APP_NAME, charSequenceToString(params.mCallScreeningAppName));
        values.put(CALL_SCREENING_COMPONENT_NAME, params.mCallScreeningComponentName);
        values.put(MISSED_REASON, Long.valueOf(params.mMissedReason));
        values.put(PRIORITY, params.mPriority);
        values.put(SUBJECT, params.mSubject);
        if (params.mPictureUri != null) {
            values.put(COMPOSER_PHOTO_URI, params.mPictureUri.toString());
        }
        values.put(IS_PHONE_ACCOUNT_MIGRATION_PENDING, params.mIsPhoneAccountMigrationPending);
        if (Flags.businessCallComposer()) {
            values.put(IS_BUSINESS_CALL, Integer.valueOf(params.mIsBusinessCall ? 1 : 0));
            values.put(ASSERTED_DISPLAY_NAME, params.mAssertedDisplayName);
        }
        if (Flags.integratedCallLogs()) {
            values.put(UUID, params.mUuid);
        }
        if ((params.mCallerInfo != null) && (params.mCallerInfo.getContactId() > 0)) {
            // Update usage information for the number associated with the contact ID.
            // We need to use both the number and the ID for obtaining a data ID since other
            // contacts may have the same number.

            final Cursor cursor;

            // We should prefer normalized one (probably coming from
            // Phone.NORMALIZED_NUMBER column) first. If it isn't available try others.
            if (params.mCallerInfo.normalizedNumber != null) {
                final String normalizedPhoneNumber = params.mCallerInfo.normalizedNumber;
                cursor = resolver.query(Phone.CONTENT_URI,
                    new String[]{Phone._ID},
                    Phone.CONTACT_ID + " =? AND " + Phone.NORMALIZED_NUMBER + " =?",
                    new String[]{String.valueOf(params.mCallerInfo.getContactId()),
                        normalizedPhoneNumber},
                    null);
            } else {
                final String phoneNumber = params.mCallerInfo.getPhoneNumber() != null
                    ? params.mCallerInfo.getPhoneNumber() : params.mNumber;
                cursor = resolver.query(
                    Uri.withAppendedPath(Callable.CONTENT_FILTER_URI,
                        Uri.encode(phoneNumber)),
                    new String[]{Phone._ID},
                    Phone.CONTACT_ID + " =?",
                    new String[]{String.valueOf(params.mCallerInfo.getContactId())},
                    null);
            }

            if (cursor != null) {
                try {
                    if (cursor.getCount() > 0 && cursor.moveToFirst()) {
                        final String dataId = cursor.getString(0);
                        updateDataUsageStatForData(resolver, dataId);
                        if (params.mDuration >= MIN_DURATION_FOR_NORMALIZED_NUMBER_UPDATE_MS
                            && params.mCallType == CallLog.Calls.OUTGOING_TYPE
                            && TextUtils.isEmpty(params.mCallerInfo.normalizedNumber)) {
                            updateNormalizedNumber(context, resolver, dataId, params.mNumber);
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        }

        /*
                Writing the calllog works in the following way:
                - All user entries
                    - if user-0 is encrypted, insert to user-0's shadow only.
                      (other users should also be encrypted, so nothing to do for other users.)
                    - if user-0 is decrypted, insert to user-0's real provider, as well as
                      all other users that are running and decrypted and should have calllog.

                - Single user entry.
                    - If the target user is encrypted, insert to its shadow.
                    - Otherwise insert to its real provider.

                When the (real) calllog provider starts, it copies entries that it missed from
                elsewhere.
                - When user-0's (real) provider starts, it copies from user-0's shadow, and clears
                  the shadow.

                - When other users (real) providers start, unless it shouldn't have calllog entries,
                     - Copy from the user's shadow, and clears the shadow.
                     - Copy from user-0's entries that are FOR_ALL_USERS = 1.  (and don't clear it.)
             */

        Uri result = null;

        final UserManager userManager = context.getSystemService(UserManager.class);
        final int currentUserId = userManager.getProcessUserId();

        if (params.mAddForAllUsers) {
            if (userManager.isUserUnlocked(UserHandle.SYSTEM)) {
                // If the user is unlocked, insert to the location provider if a location is
                // provided. Do not store location if the device is still locked -- this
                // puts it into device-encrypted storage instead of credential-encrypted
                // storage.
                Uri locationUri = maybeInsertLocation(params, resolver, UserHandle.SYSTEM);
                if (locationUri != null) {
                    values.put(CallLog.Calls.LOCATION, locationUri.toString());
                }
            }

            // First, insert to the system user.
            final Uri uriForSystem = addEntryAndRemoveExpiredEntries(
                context, userManager, UserHandle.SYSTEM, values);
            if (uriForSystem == null
                || SHADOW_AUTHORITY.equals(uriForSystem.getAuthority())) {
                // This means the system user is still encrypted and the entry has inserted
                // into the shadow.  This means other users are still all encrypted.
                // Nothing further to do; just return null.
                return null;
            }
            if (UserHandle.USER_SYSTEM == currentUserId) {
                result = uriForSystem;
            }

            // Otherwise, insert to all other users that are running and unlocked.

            final List<UserHandle> users = userManager.getUserHandles(true);

            final int count = users.size();
            for (int i = 0; i < count; i++) {
                final UserHandle userHandle = users.get(i);
                final int userId = userHandle.getIdentifier();

                if (userHandle.isSystem()) {
                    // Already written.
                    continue;
                }

                if (!shouldHaveSharedCallLogEntries(context, userManager, userHandle)) {
                    // Shouldn't have calllog entries.
                    continue;
                }

                // For other users, we write only when they're running *and* decrypted.
                // Other providers will copy from the system user's real provider, when they
                // start.
                if (userManager.isUserRunning(userHandle)
                    && userManager.isUserUnlocked(userHandle)) {
                    Uri locationUri = maybeInsertLocation(params, resolver, userHandle);
                    if (locationUri != null) {
                        values.put(CallLog.Calls.LOCATION, locationUri.toString());
                    } else {
                        values.put(CallLog.Calls.LOCATION, (String) null);
                    }
                    final Uri uri = addEntryAndRemoveExpiredEntries(context, userManager,
                        userHandle, values);
                    if (userId == currentUserId) {
                        result = uri;
                    }
                }
            }
        } else {
            // Single-user entry. Just write to that user, assuming it's running.  If the
            // user is encrypted, we write to the shadow calllog.
            final UserHandle targetUserHandle = params.mUserToBeInsertedTo != null
                ? params.mUserToBeInsertedTo
                : UserHandle.of(currentUserId);

            if (userManager.isUserRunning(targetUserHandle)
                && userManager.isUserUnlocked(targetUserHandle)) {
                Uri locationUri = maybeInsertLocation(params, resolver, targetUserHandle);
                if (locationUri != null) {
                    values.put(CallLog.Calls.LOCATION, locationUri.toString());
                } else {
                    values.put(CallLog.Calls.LOCATION, (String) null);
                }
            }

            result = addEntryAndRemoveExpiredEntries(context, userManager, targetUserHandle,
                values);
        }
        return result;
    }

    private static String charSequenceToString(CharSequence sequence) {
        return sequence == null ? null : sequence.toString();
    }

    private static boolean shouldHaveSharedCallLogEntries(
            Context context, UserManager userManager, UserHandle userHandle) {
        if (userManager.hasUserRestrictionForUser(
                UserManager.DISALLOW_OUTGOING_CALLS, userHandle)) {
            return false;
        }
        return isProfile(context, userHandle);
    }

    private static boolean isProfile(Context context, UserHandle userHandle) {
        try {
            return context.createContextAsUser(userHandle, 0)
                    .getSystemService(UserManager.class).isProfile();
        } catch (IllegalStateException e) {
            Log.e(LOG_TAG, e + "Error while creating context as user = " + userHandle);
        }
        return false;
    }

    private static Uri addEntryAndRemoveExpiredEntries(Context context, UserManager userManager,
        UserHandle user, ContentValues values) {
        final ContentResolver resolver = context.getContentResolver();

        // Since we're doing this operation on behalf of an app, we only
        // want to use the actual "unlocked" state.
        final Uri uri = ContentProvider.maybeAddUserId(
            userManager.isUserUnlocked(user) ? CONTENT_URI : SHADOW_CONTENT_URI,
            user.getIdentifier());

        Log.i(LOG_TAG, String.format(Locale.getDefault(),
            "addEntryAndRemoveExpiredEntries: provider uri=%s", uri));

        try {
            // When cleaning up the call log, try to delete older call long entries on a per
            // PhoneAccount basis first.  There can be multiple ConnectionServices causing
            // the addition of entries in the call log.  With the introduction of Self-Managed
            // ConnectionServices, we want to ensure that a misbehaving self-managed CS cannot
            // spam the call log with its own entries, causing entries from Telephony to be
            // removed.
            final Uri result = resolver.insert(uri, values);
            if (result != null) {
                String lastPathSegment = result.getLastPathSegment();
                // When inserting into the call log, if ContentProvider#insert detect an appops
                // denial a non-null "silent rejection" URI is returned which ends in 0.
                // Example: content://call_log/calls/0
                // The 0 in the last part of the path indicates a fake call id of 0.
                // A denial when logging calls from the platform is bad; there is no other
                // logging to indicate that this has happened so we will check for that scenario
                // here and log a warning so we have a hint as to what is going on.
                if (lastPathSegment != null && lastPathSegment.equals("0")) {
                    Log.w(LOG_TAG, "Failed to insert into call log due to appops denial;"
                        + " resultUri=" + result);
                }
            } else {
                Log.w(LOG_TAG, "Failed to insert into call log; null result uri.");
            }

            int numDeleted;
            final String phoneAccountId =
                values.containsKey(PHONE_ACCOUNT_ID)
                    ? values.getAsString(PHONE_ACCOUNT_ID) : null;
            final String phoneAccountComponentName =
                values.containsKey(PHONE_ACCOUNT_COMPONENT_NAME)
                    ? values.getAsString(PHONE_ACCOUNT_COMPONENT_NAME) : null;
            int maxCallLogSize = DEFAULT_MAX_CALL_LOG_SIZE;
            if (!TextUtils.isEmpty(phoneAccountId)
                && !TextUtils.isEmpty(phoneAccountComponentName)) {
                if (android.provider.Flags.allowConfigMaximumCallLogEntriesPerSim()
                    && TELEPHONY_COMPONENT_NAME
                    .flattenToString().equals(phoneAccountComponentName)) {
                    maxCallLogSize = context.getResources().getInteger(
                        com.android.internal.R.integer.config_maximumCallLogEntriesPerSim);
                }
                // Only purge entries for the same phone account.
                numDeleted = resolver.delete(uri, "_id IN "
                        + "(SELECT _id FROM calls"
                        + " WHERE " + PHONE_ACCOUNT_COMPONENT_NAME + " = ?"
                        + " AND " + PHONE_ACCOUNT_ID + " = ?"
                        + " ORDER BY " + DEFAULT_SORT_ORDER
                        + " LIMIT -1 OFFSET " + maxCallLogSize + ")",
                    new String[]{phoneAccountComponentName, phoneAccountId}
                );
            } else {
                // No valid phone account specified, so default to the old behavior.
                numDeleted = resolver.delete(uri, "_id IN "
                    + "(SELECT _id FROM calls ORDER BY " + DEFAULT_SORT_ORDER
                    + " LIMIT -1 OFFSET " + maxCallLogSize + ")", null);
            }
            Log.i(LOG_TAG, "addEntry: cleaned up " + numDeleted + " old entries");

            return result;
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "Failed to insert calllog", e);
            // Even though we make sure the target user is running and decrypted before calling
            // this method, there's a chance that the user just got shut down, in which case
            // we'll still get "IllegalArgumentException: Unknown URL content://call_log/calls".
            return null;
        }
    }

    private static Uri maybeInsertLocation(AddCallParams params, ContentResolver resolver,
        UserHandle user) {
        if (Double.isNaN(params.mLatitude) || Double.isNaN(params.mLongitude)) {
            return null;
        }
        ContentValues locationValues = new ContentValues();
        locationValues.put(CallLog.Locations.LATITUDE, params.mLatitude);
        locationValues.put(CallLog.Locations.LONGITUDE, params.mLongitude);
        Uri locationUri = ContentProvider.maybeAddUserId(CallLog.Locations.CONTENT_URI,
            user.getIdentifier());
        try {
            return resolver.insert(locationUri, locationValues);
        } catch (SecurityException e) {
            // This can happen if the caller doesn't have location permissions. If that's the
            // case just skip the insertion.
            Log.w(LOG_TAG, "Skipping inserting location for " + e);
            return null;
        }
    }

    private static void updateDataUsageStatForData(ContentResolver resolver, String dataId) {
        final Uri feedbackUri = DataUsageFeedback.FEEDBACK_URI.buildUpon()
            .appendPath(dataId)
            .appendQueryParameter(DataUsageFeedback.USAGE_TYPE,
                DataUsageFeedback.USAGE_TYPE_CALL)
            .build();
        resolver.update(feedbackUri, new ContentValues(), null, null);
    }

    /*
     * Update the normalized phone number for the given dataId in the ContactsProvider, based
     * on the user's current country.
     */
    private static void updateNormalizedNumber(Context context, ContentResolver resolver,
        String dataId, String number) {
        if (TextUtils.isEmpty(number) || TextUtils.isEmpty(dataId)) {
            return;
        }
        final String countryIso = getCurrentCountryIso(context);
        if (TextUtils.isEmpty(countryIso)) {
            return;
        }
        final String normalizedNumber = PhoneNumberUtils.formatNumberToE164(number, countryIso);
        if (TextUtils.isEmpty(normalizedNumber)) {
            return;
        }
        final ContentValues values = new ContentValues();
        values.put(Phone.NORMALIZED_NUMBER, normalizedNumber);
        resolver.update(Data.CONTENT_URI, values, Data._ID + "=?", new String[]{dataId});
    }

    /**
     * Remap network specified number presentation types TelecomManager.PRESENTATION_xxx to calllog
     * number presentation types Calls.PRESENTATION_xxx, in order to insulate the persistent calllog
     * from any future radio changes. If the number field is empty set the presentation type to
     * Unknown.
     */
    private static int getLogNumberPresentation(String number, int presentation) {
        if (presentation == TelecomManager.PRESENTATION_RESTRICTED) {
            return presentation;
        }

        if (presentation == TelecomManager.PRESENTATION_PAYPHONE) {
            return presentation;
        }

        if (presentation == TelecomManager.PRESENTATION_UNAVAILABLE) {
            return PRESENTATION_UNAVAILABLE;
        }

        if (TextUtils.isEmpty(number)
            || presentation == TelecomManager.PRESENTATION_UNKNOWN) {
            return PRESENTATION_UNKNOWN;
        }

        return PRESENTATION_ALLOWED;
    }

    private static String getLogAccountAddress(Context context,
        PhoneAccountHandle accountHandle) {
        TelecomManager tm = null;
        try {
            tm = context.getSystemService(TelecomManager.class);
        } catch (UnsupportedOperationException e) {
            if (VERBOSE_LOG) {
                Log.v(LOG_TAG, "No TelecomManager found to get account address.");
            }
        }

        String accountAddress = null;
        if (tm != null && accountHandle != null) {
            PhoneAccount account = tm.getPhoneAccount(accountHandle);
            if (account != null) {
                Uri address = account.getSubscriptionAddress();
                if (address != null) {
                    accountAddress = address.getSchemeSpecificPart();
                }
            }
        }
        return accountAddress;
    }

    private static String getCurrentCountryIso(Context context) {
        String countryIso = null;
        final CountryDetector detector = context.getSystemService(CountryDetector.class);
        if (detector != null) {
            final Country country = detector.detectCountry();
            if (country != null) {
                countryIso = country.getCountryIso();
            }
        }
        return countryIso;
    }

    /**
     * Used as an argument to {@link Calls#addCall(Context, AddCallParams)}.
     *
     * Contains details to log about a call.
     */
    public static class AddCallParams {

        private final CallerInfo mCallerInfo;
        private final String mPostDialDigits;
        private final String mViaNumber;
        private final int mPresentation;
        private final int mCallType;
        private final int mFeatures;
        private final PhoneAccountHandle mAccountHandle;
        private final long mStart;
        private final int mDuration;
        private final long mDataUsage;
        private final boolean mAddForAllUsers;
        private final UserHandle mUserToBeInsertedTo;
        private final boolean mIsRead;
        private final int mCallBlockReason;
        private final CharSequence mCallScreeningAppName;
        private final String mCallScreeningComponentName;
        private final long mMissedReason;
        private final int mPriority;
        private final String mSubject;
        private final Uri mPictureUri;
        private final int mIsPhoneAccountMigrationPending;
        private String mNumber;
        private double mLatitude = Double.NaN;
        private double mLongitude = Double.NaN;
        private boolean mIsBusinessCall;
        private String mAssertedDisplayName;
        private String mUuid;

        private AddCallParams(CallerInfo callerInfo, String number, String postDialDigits,
            String viaNumber, int presentation, int callType, int features,
            PhoneAccountHandle accountHandle, long start, int duration, long dataUsage,
            boolean addForAllUsers, UserHandle userToBeInsertedTo, boolean isRead,
            int callBlockReason,
            CharSequence callScreeningAppName, String callScreeningComponentName,
            long missedReason,
            int priority, String subject, double latitude, double longitude, Uri pictureUri,
            int isPhoneAccountMigrationPending) {
            mCallerInfo = callerInfo;
            mNumber = number;
            mPostDialDigits = postDialDigits;
            mViaNumber = viaNumber;
            mPresentation = presentation;
            mCallType = callType;
            mFeatures = features;
            mAccountHandle = accountHandle;
            mStart = start;
            mDuration = duration;
            mDataUsage = dataUsage;
            mAddForAllUsers = addForAllUsers;
            mUserToBeInsertedTo = userToBeInsertedTo;
            mIsRead = isRead;
            mCallBlockReason = callBlockReason;
            mCallScreeningAppName = callScreeningAppName;
            mCallScreeningComponentName = callScreeningComponentName;
            mMissedReason = missedReason;
            mPriority = priority;
            mSubject = subject;
            mLatitude = latitude;
            mLongitude = longitude;
            mPictureUri = pictureUri;
            mIsPhoneAccountMigrationPending = isPhoneAccountMigrationPending;
        }

        private AddCallParams(CallerInfo callerInfo, String number, String postDialDigits,
            String viaNumber, int presentation, int callType, int features,
            PhoneAccountHandle accountHandle, long start, int duration, long dataUsage,
            boolean addForAllUsers, UserHandle userToBeInsertedTo, boolean isRead,
            int callBlockReason,
            CharSequence callScreeningAppName, String callScreeningComponentName,
            long missedReason,
            int priority, String subject, double latitude, double longitude, Uri pictureUri,
            int isPhoneAccountMigrationPending, boolean isBusinessCall,
            String assertedDisplayName) {
            mCallerInfo = callerInfo;
            mNumber = number;
            mPostDialDigits = postDialDigits;
            mViaNumber = viaNumber;
            mPresentation = presentation;
            mCallType = callType;
            mFeatures = features;
            mAccountHandle = accountHandle;
            mStart = start;
            mDuration = duration;
            mDataUsage = dataUsage;
            mAddForAllUsers = addForAllUsers;
            mUserToBeInsertedTo = userToBeInsertedTo;
            mIsRead = isRead;
            mCallBlockReason = callBlockReason;
            mCallScreeningAppName = callScreeningAppName;
            mCallScreeningComponentName = callScreeningComponentName;
            mMissedReason = missedReason;
            mPriority = priority;
            mSubject = subject;
            mLatitude = latitude;
            mLongitude = longitude;
            mPictureUri = pictureUri;
            mIsPhoneAccountMigrationPending = isPhoneAccountMigrationPending;
            mIsBusinessCall = isBusinessCall;
            mAssertedDisplayName = assertedDisplayName;
        }

        private AddCallParams(CallerInfo callerInfo, String number, String postDialDigits,
                String viaNumber, int presentation, int callType, int features,
                PhoneAccountHandle accountHandle, long start, int duration, long dataUsage,
                boolean addForAllUsers, UserHandle userToBeInsertedTo, boolean isRead,
                int callBlockReason,
                CharSequence callScreeningAppName, String callScreeningComponentName,
                long missedReason,
                int priority, String subject, double latitude, double longitude, Uri pictureUri,
                int isPhoneAccountMigrationPending, boolean isBusinessCall,
                String assertedDisplayName, String uuid) {
            this(callerInfo, number, postDialDigits, viaNumber, presentation, callType, features,
                    accountHandle, start, duration, dataUsage, addForAllUsers, userToBeInsertedTo,
                    isRead, callBlockReason, callScreeningAppName, callScreeningComponentName,
                    missedReason, priority, subject, latitude, longitude, pictureUri,
                    isPhoneAccountMigrationPending, isBusinessCall, assertedDisplayName);
            mUuid = uuid;
        }

        /**
         * Builder for the add-call parameters.
         */
        public static final class AddCallParametersBuilder {

            public static final int MAX_NUMBER_OF_CHARACTERS = 256;
            private CallerInfo mCallerInfo;
            private String mNumber;
            private String mPostDialDigits;
            private String mViaNumber;
            private int mPresentation = TelecomManager.PRESENTATION_UNKNOWN;
            private int mCallType = CallLog.Calls.INCOMING_TYPE;
            private int mFeatures;
            private PhoneAccountHandle mAccountHandle;
            private long mStart;
            private int mDuration;
            private Long mDataUsage = Long.MIN_VALUE;
            private boolean mAddForAllUsers;
            private UserHandle mUserToBeInsertedTo;
            private boolean mIsRead;
            private int mCallBlockReason = CallLog.Calls.BLOCK_REASON_NOT_BLOCKED;
            private CharSequence mCallScreeningAppName;
            private String mCallScreeningComponentName;
            private long mMissedReason = CallLog.Calls.MISSED_REASON_NOT_MISSED;
            private int mPriority = CallLog.Calls.PRIORITY_NORMAL;
            private String mSubject;
            private double mLatitude = Double.NaN;
            private double mLongitude = Double.NaN;
            private Uri mPictureUri;
            private int mIsPhoneAccountMigrationPending;
            private boolean mIsBusinessCall;
            private String mAssertedDisplayName;
            private String mUuid;

            /**
             * @param callerInfo the CallerInfo object to get the target contact from.
             */
            public @NonNull AddCallParametersBuilder setCallerInfo(
                @NonNull CallerInfo callerInfo) {
                mCallerInfo = callerInfo;
                return this;
            }

            /**
             * @param number the phone number to be added to the calls db
             */
            public @NonNull AddCallParametersBuilder setNumber(@NonNull String number) {
                mNumber = number;
                return this;
            }

            /**
             * @param postDialDigits the post-dial digits that were dialed after the number, if
             *     it was outgoing. Otherwise it is ''.
             */
            public @NonNull AddCallParametersBuilder setPostDialDigits(
                @NonNull String postDialDigits) {
                mPostDialDigits = postDialDigits;
                return this;
            }

            /**
             * @param viaNumber the secondary number that the incoming call received with. If
             *     the call was received with the SIM assigned number, then this field must be ''.
             */
            public @NonNull AddCallParametersBuilder setViaNumber(@NonNull String viaNumber) {
                mViaNumber = viaNumber;
                return this;
            }

            /**
             * @param presentation enum value from TelecomManager.PRESENTATION_xxx, which is set
             *     by the network and denotes the number presenting rules for "allowed", "payphone",
             *     "restricted" or "unknown"
             */
            public @NonNull AddCallParametersBuilder setPresentation(int presentation) {
                mPresentation = presentation;
                return this;
            }

            /**
             * @param callType enumerated values for "incoming", "outgoing", or "missed"
             */
            public @NonNull AddCallParametersBuilder setCallType(int callType) {
                mCallType = callType;
                return this;
            }

            /**
             * @param features features of the call (e.g. Video).
             */
            public @NonNull AddCallParametersBuilder setFeatures(int features) {
                mFeatures = features;
                return this;
            }

            /**
             * @param accountHandle The accountHandle object identifying the provider of the
             *     call
             */
            public @NonNull AddCallParametersBuilder setAccountHandle(
                @NonNull PhoneAccountHandle accountHandle) {
                mAccountHandle = accountHandle;
                return this;
            }

            /**
             * @param start time stamp for the call in milliseconds
             */
            public @NonNull AddCallParametersBuilder setStart(long start) {
                mStart = start;
                return this;
            }

            /**
             * @param duration call duration in seconds
             */
            public @NonNull AddCallParametersBuilder setDuration(int duration) {
                mDuration = duration;
                return this;
            }

            /**
             * @param dataUsage data usage for the call in bytes or {@link Long#MIN_VALUE} if
             *     data usage was not tracked for the call.
             */
            public @NonNull AddCallParametersBuilder setDataUsage(long dataUsage) {
                mDataUsage = dataUsage;
                return this;
            }

            /**
             * @param addForAllUsers If true, the call is added to the call log of all currently
             *     running users. The caller must have the MANAGE_USERS permission if this is true.
             */
            public @NonNull AddCallParametersBuilder setAddForAllUsers(
                boolean addForAllUsers) {
                mAddForAllUsers = addForAllUsers;
                return this;
            }

            /**
             * @param userToBeInsertedTo {@link UserHandle} of user that the call is going to be
             *     inserted to. null if it is inserted to the current user. The value is ignored if
             *     {@link #setAddForAllUsers} is called with {@code true}.
             */
            @SuppressLint("UserHandleName")
            public @NonNull AddCallParametersBuilder setUserToBeInsertedTo(
                @NonNull UserHandle userToBeInsertedTo) {
                mUserToBeInsertedTo = userToBeInsertedTo;
                return this;
            }

            /**
             * @param isRead Flag to show if the missed call log has been read by the user or
             *     not. Used for call log restore of missed calls.
             */
            public @NonNull AddCallParametersBuilder setIsRead(boolean isRead) {
                mIsRead = isRead;
                return this;
            }

            /**
             * @param callBlockReason The reason why the call is blocked.
             */
            public @NonNull AddCallParametersBuilder setCallBlockReason(int callBlockReason) {
                mCallBlockReason = callBlockReason;
                return this;
            }

            /**
             * @param callScreeningAppName The call screening application name which block the
             *     call.
             */
            public @NonNull AddCallParametersBuilder setCallScreeningAppName(
                @NonNull CharSequence callScreeningAppName) {
                mCallScreeningAppName = callScreeningAppName;
                return this;
            }

            /**
             * @param callScreeningComponentName The call screening component name which blocked
             *     the call.
             */
            public @NonNull AddCallParametersBuilder setCallScreeningComponentName(
                @NonNull String callScreeningComponentName) {
                mCallScreeningComponentName = callScreeningComponentName;
                return this;
            }

            /**
             * @param missedReason The encoded missed information of the call.
             */
            public @NonNull AddCallParametersBuilder setMissedReason(long missedReason) {
                mMissedReason = missedReason;
                return this;
            }

            /**
             * @param priority The priority of the call, either {@link Calls#PRIORITY_NORMAL} or
             *     {@link Calls#PRIORITY_URGENT} as sent via call composer
             */
            public @NonNull AddCallParametersBuilder setPriority(int priority) {
                mPriority = priority;
                return this;
            }

            /**
             * @param subject The subject as sent via call composer.
             */
            public @NonNull AddCallParametersBuilder setSubject(@NonNull String subject) {
                mSubject = subject;
                return this;
            }

            /**
             * @param latitude Latitude of the location sent via call composer.
             */
            public @NonNull AddCallParametersBuilder setLatitude(double latitude) {
                mLatitude = latitude;
                return this;
            }

            /**
             * @param longitude Longitude of the location sent via call composer.
             */
            public @NonNull AddCallParametersBuilder setLongitude(double longitude) {
                mLongitude = longitude;
                return this;
            }

            /**
             * @param pictureUri {@link Uri} returned from {@link #storeCallComposerPicture}.
             *     Associates that stored picture with this call in the log.
             */
            public @NonNull AddCallParametersBuilder setPictureUri(@NonNull Uri pictureUri) {
                mPictureUri = pictureUri;
                return this;
            }

            /**
             * @param isPhoneAccountMigrationPending whether the phone account migration is
             *     pending
             */
            public @NonNull AddCallParametersBuilder setIsPhoneAccountMigrationPending(
                int isPhoneAccountMigrationPending) {
                mIsPhoneAccountMigrationPending = isPhoneAccountMigrationPending;
                return this;
            }

            /**
             * @param isBusinessCall should be set if the caller is a business call
             */
            public @NonNull AddCallParametersBuilder setIsBusinessCall(boolean isBusinessCall) {
                mIsBusinessCall = isBusinessCall;
                return this;
            }

            /**
             * @param assertedDisplayName the asserted display name associated with the business
             *     call
             * @throws IllegalArgumentException if the assertedDisplayName is over 256
             *     characters
             */
            public @NonNull AddCallParametersBuilder setAssertedDisplayName(
                String assertedDisplayName) {
                if (assertedDisplayName != null
                    && assertedDisplayName.length() > MAX_NUMBER_OF_CHARACTERS) {
                    throw new IllegalArgumentException("assertedDisplayName exceeds the character"
                        + " limit of " + MAX_NUMBER_OF_CHARACTERS + ".");
                }
                mAssertedDisplayName = assertedDisplayName;
                return this;
            }

            /**
             * @param uuid the uuid associated with the call.
             * @throws IllegalArgumentException if the assertedDisplayName is over 256
             *     characters
             */
            public @NonNull AddCallParametersBuilder setUuid(
                    String uuid) {
                if (uuid != null
                        && uuid.length() > MAX_NUMBER_OF_CHARACTERS) {
                    throw new IllegalArgumentException("assertedDisplayName exceeds the character"
                            + " limit of " + MAX_NUMBER_OF_CHARACTERS + ".");
                }

                // Validate the uuid. An illegal argument exception will be thrown if the format
                // doesn't conform.
                try {
                    java.util.UUID.fromString(uuid);
                    mUuid = uuid;
                } catch (Exception e) {
                    Log.e(LOG_TAG, e + "Invalid uuid passed in: " + uuid);
                }
                return this;
            }

            /**
             * Builds the object
             */
            public @NonNull AddCallParams build() {
                if (Flags.businessCallComposer()) {
                    if (Flags.integratedCallLogs()) {
                        return new AddCallParams(mCallerInfo, mNumber, mPostDialDigits, mViaNumber,
                                mPresentation, mCallType, mFeatures, mAccountHandle, mStart,
                                mDuration, mDataUsage, mAddForAllUsers, mUserToBeInsertedTo,
                                mIsRead, mCallBlockReason,
                                mCallScreeningAppName, mCallScreeningComponentName, mMissedReason,
                                mPriority, mSubject, mLatitude, mLongitude, mPictureUri,
                                mIsPhoneAccountMigrationPending, mIsBusinessCall,
                                mAssertedDisplayName, mUuid);
                    } else {
                        return new AddCallParams(mCallerInfo, mNumber, mPostDialDigits, mViaNumber,
                                mPresentation, mCallType, mFeatures, mAccountHandle, mStart,
                                mDuration, mDataUsage, mAddForAllUsers, mUserToBeInsertedTo,
                                mIsRead, mCallBlockReason,
                                mCallScreeningAppName, mCallScreeningComponentName, mMissedReason,
                                mPriority, mSubject, mLatitude, mLongitude, mPictureUri,
                                mIsPhoneAccountMigrationPending, mIsBusinessCall,
                                mAssertedDisplayName);
                    }
                } else {
                    return new AddCallParams(mCallerInfo, mNumber, mPostDialDigits, mViaNumber,
                        mPresentation, mCallType, mFeatures, mAccountHandle, mStart, mDuration,
                        mDataUsage, mAddForAllUsers, mUserToBeInsertedTo, mIsRead,
                        mCallBlockReason,
                        mCallScreeningAppName, mCallScreeningComponentName, mMissedReason,
                        mPriority, mSubject, mLatitude, mLongitude, mPictureUri,
                        mIsPhoneAccountMigrationPending);
                }
            }
        }
    }
}
