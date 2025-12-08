/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.telecom.metrics;

import static com.android.server.telecom.TelecomStatsLog.TELECOM_API_STATS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.telecom.Log;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.TelecomStatsLog;
import com.android.server.telecom.nano.PulledAtomsClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ApiStats extends TelecomPulledAtom {
    public static final int API_UNSPECIFIC = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_UNSPECIFIED;
    public static final int API_ACCEPTHANDOVER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_ACCEPT_HANDOVER;
    public static final int API_ACCEPTRINGINGCALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_ACCEPT_RINGING_CALL;
    public static final int API_ACCEPTRINGINGCALLWITHVIDEOSTATE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_ACCEPT_RINGING_CALL_WITH_VIDEO_STATE;
    public static final int API_ADDCALL = TelecomStatsLog.TELECOM_API_STATS__API_NAME__API_ADD_CALL;
    public static final int API_ADDNEWINCOMINGCALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_ADD_NEW_INCOMING_CALL;
    public static final int API_ADDNEWINCOMINGCONFERENCE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_ADD_NEW_INCOMING_CONFERENCE;
    public static final int API_ADDNEWUNKNOWNCALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_ADD_NEW_UNKNOWN_CALL;
    public static final int API_CANCELMISSEDCALLSNOTIFICATION = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_CANCEL_MISSED_CALLS_NOTIFICATION;
    public static final int API_CLEARACCOUNTS = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_CLEAR_ACCOUNTS;
    public static final int API_CREATELAUNCHEMERGENCYDIALERINTENT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_CREATE_LAUNCH_EMERGENCY_DIALER_INTENT;
    public static final int API_CREATEMANAGEBLOCKEDNUMBERSINTENT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_CREATE_MANAGE_BLOCKED_NUMBERS_INTENT;
    public static final int API_DUMP = TelecomStatsLog.TELECOM_API_STATS__API_NAME__API_DUMP;
    public static final int API_DUMPCALLANALYTICS = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_DUMP_CALL_ANALYTICS;
    public static final int API_ENABLEPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_ENABLE_PHONE_ACCOUNT;
    public static final int API_ENDCALL = TelecomStatsLog.TELECOM_API_STATS__API_NAME__API_END_CALL;
    public static final int API_GETADNURIFORPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_ADN_URI_FOR_PHONE_ACCOUNT;
    public static final int API_GETALLPHONEACCOUNTHANDLES = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_ALL_PHONE_ACCOUNT_HANDLES;
    public static final int API_GETALLPHONEACCOUNTS = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_ALL_PHONE_ACCOUNTS;
    public static final int API_GETALLPHONEACCOUNTSCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_ALL_PHONE_ACCOUNTS_COUNT;
    public static final int API_GETCALLCAPABLEPHONEACCOUNTS = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_CALL_CAPABLE_PHONE_ACCOUNTS;
    public static final int API_GETCALLSTATE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_CALL_STATE;
    public static final int API_GETCALLSTATEUSINGPACKAGE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_CALL_STATE_USING_PACKAGE;
    public static final int API_GETCURRENTTTYMODE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_CURRENT_TTY_MODE;
    public static final int API_GETDEFAULTDIALERPACKAGE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_DEFAULT_DIALER_PACKAGE;
    public static final int API_GETDEFAULTDIALERPACKAGEFORUSER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_DEFAULT_DIALER_PACKAGE_FOR_USER;
    public static final int API_GETDEFAULTOUTGOINGPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_DEFAULT_OUTGOING_PHONE_ACCOUNT;
    public static final int API_GETDEFAULTPHONEAPP = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_DEFAULT_PHONE_APP;
    public static final int API_GETLINE1NUMBER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_LINE1_NUMBER;
    public static final int API_GETOWNSELFMANAGEDPHONEACCOUNTS = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_OWN_SELF_MANAGED_PHONE_ACCOUNTS;
    public static final int API_GETPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_PHONE_ACCOUNT;
    public static final int API_GETPHONEACCOUNTSFORPACKAGE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_PHONE_ACCOUNTS_FOR_PACKAGE;
    public static final int API_GETPHONEACCOUNTSSUPPORTINGSCHEME = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_PHONE_ACCOUNTS_SUPPORTING_SCHEME;
    public static final int API_GETREGISTEREDPHONEACCOUNTS = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_REGISTERED_PHONE_ACCOUNTS;
    public static final int API_GETSELFMANAGEDPHONEACCOUNTS = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_SELF_MANAGED_PHONE_ACCOUNTS;
    public static final int API_GETSIMCALLMANAGER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_SIM_CALL_MANAGER;
    public static final int API_GETSIMCALLMANAGERFORUSER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_SIM_CALL_MANAGER_FOR_USER;
    public static final int API_GETSYSTEMDIALERPACKAGE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_SYSTEM_DIALER_PACKAGE;
    public static final int API_GETUSERSELECTEDOUTGOINGPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_USER_SELECTED_OUTGOING_PHONE_ACCOUNT;
    public static final int API_GETVOICEMAILNUMBER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_GET_VOICE_MAIL_NUMBER;
    public static final int API_HANDLEPINMMI = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_HANDLE_PIN_MMI;
    public static final int API_HANDLEPINMMIFORPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_HANDLE_PIN_MMI_FOR_PHONE_ACCOUNT;
    public static final int API_HASMANAGEONGOINGCALLSPERMISSION = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_HAS_MANAGE_ONGOING_CALLS_PERMISSION;
    public static final int API_ISINCALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_IN_CALL;
    public static final int API_ISINCOMINGCALLPERMITTED = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_IN_EMERGENCY_CALL;
    public static final int API_ISINEMERGENCYCALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_IN_MANAGED_CALL;
    public static final int API_ISINMANAGEDCALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_IN_SELF_MANAGED_CALL;
    public static final int API_ISINSELFMANAGEDCALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_INCOMING_CALL_PERMITTED;
    public static final int API_ISOUTGOINGCALLPERMITTED = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_OUTGOING_CALL_PERMITTED;
    public static final int API_ISRINGING = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_RINGING;
    public static final int API_ISTTYSUPPORTED = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_TTY_SUPPORTED;
    public static final int API_ISVOICEMAILNUMBER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_IS_VOICE_MAIL_NUMBER;
    public static final int API_PLACECALL = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_PLACE_CALL;
    public static final int API_REGISTERPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_REGISTER_PHONE_ACCOUNT;
    public static final int API_SETDEFAULTDIALER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_SET_DEFAULT_DIALER;
    public static final int API_SETUSERSELECTEDOUTGOINGPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_SET_USER_SELECTED_OUTGOING_PHONE_ACCOUNT;
    public static final int API_SHOWINCALLSCREEN = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_SHOW_IN_CALL_SCREEN;
    public static final int API_SILENCERINGER = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_SILENCE_RINGER;
    public static final int API_STARTCONFERENCE = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_START_CONFERENCE;
    public static final int API_UNREGISTERPHONEACCOUNT = TelecomStatsLog
            .TELECOM_API_STATS__API_NAME__API_UNREGISTER_PHONE_ACCOUNT;
    public static final int API_GETCALLCONNECTEDINDICATORPREF =
            TelecomStatsLog.TELECOM_API_STATS__API_NAME__API_GET_CALL_CONNECTED_INDICATOR_PREF;
    public static final int API_SETCALLCONNECTEDINDICATORPREF =
            TelecomStatsLog.TELECOM_API_STATS__API_NAME__API_SET_CALL_CONNECTED_INDICATOR_PREF;
    public static final int RESULT_UNKNOWN = TelecomStatsLog
            .TELECOM_API_STATS__API_RESULT__RESULT_UNKNOWN;
    public static final int RESULT_NORMAL = TelecomStatsLog
            .TELECOM_API_STATS__API_RESULT__RESULT_SUCCESS;
    public static final int RESULT_PERMISSION = TelecomStatsLog
            .TELECOM_API_STATS__API_RESULT__RESULT_PERMISSION;
    public static final int RESULT_EXCEPTION = TelecomStatsLog
            .TELECOM_API_STATS__API_RESULT__RESULT_EXCEPTION;
    private static final String TAG = ApiStats.class.getSimpleName();
    private static final String FILE_NAME = "api_stats";
    private Map<ApiEvent, Integer> mApiStatsMap;

    public ApiStats(@NonNull Context context, @NonNull Looper looper, boolean isTestMode) {
        super(context, looper, isTestMode);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return TELECOM_API_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.telecomApiStats.length != 0) {
            Arrays.stream(mPulledAtoms.telecomApiStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getApiName(), v.getUid(), v.getApiResult(), v.getCount())));
            mApiStatsMap.clear();
            onAggregate();
        }
        return StatsManager.PULL_SUCCESS;
    }

    @Override
    protected synchronized void onLoad() {
        if (mPulledAtoms.telecomApiStats != null) {
            mApiStatsMap = new HashMap<>();
            for (PulledAtomsClass.TelecomApiStats v : mPulledAtoms.telecomApiStats) {
                mApiStatsMap.put(new ApiEvent(v.getApiName(), v.getUid(), v.getApiResult()),
                        v.getCount());
            }
            mLastPulledTimestamps = mPulledAtoms.getTelecomApiStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        Log.d(TAG, "onAggregate: %s", mApiStatsMap);
        clearAtoms();
        if (mApiStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setTelecomApiStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.telecomApiStats =
                new PulledAtomsClass.TelecomApiStats[mApiStatsMap.size()];
        int[] index = new int[1];
        mApiStatsMap.forEach((k, v) -> {
            mPulledAtoms.telecomApiStats[index[0]] = new PulledAtomsClass.TelecomApiStats();
            mPulledAtoms.telecomApiStats[index[0]].setApiName(k.mId);
            mPulledAtoms.telecomApiStats[index[0]].setUid(k.mCallerUid);
            mPulledAtoms.telecomApiStats[index[0]].setApiResult(k.mResult);
            mPulledAtoms.telecomApiStats[index[0]].setCount(v);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    public void log(@NonNull ApiEvent event) {
        post(() -> {
            mApiStatsMap.put(event, mApiStatsMap.getOrDefault(event, 0) + 1);
            onAggregate();
        });
    }

    @IntDef(prefix = "API", value = {
            API_UNSPECIFIC,
            API_ACCEPTHANDOVER,
            API_ACCEPTRINGINGCALL,
            API_ACCEPTRINGINGCALLWITHVIDEOSTATE,
            API_ADDCALL,
            API_ADDNEWINCOMINGCALL,
            API_ADDNEWINCOMINGCONFERENCE,
            API_ADDNEWUNKNOWNCALL,
            API_CANCELMISSEDCALLSNOTIFICATION,
            API_CLEARACCOUNTS,
            API_CREATELAUNCHEMERGENCYDIALERINTENT,
            API_CREATEMANAGEBLOCKEDNUMBERSINTENT,
            API_DUMP,
            API_DUMPCALLANALYTICS,
            API_ENABLEPHONEACCOUNT,
            API_ENDCALL,
            API_GETADNURIFORPHONEACCOUNT,
            API_GETALLPHONEACCOUNTHANDLES,
            API_GETALLPHONEACCOUNTS,
            API_GETALLPHONEACCOUNTSCOUNT,
            API_GETCALLCAPABLEPHONEACCOUNTS,
            API_GETCALLSTATE,
            API_GETCALLSTATEUSINGPACKAGE,
            API_GETCURRENTTTYMODE,
            API_GETDEFAULTDIALERPACKAGE,
            API_GETDEFAULTDIALERPACKAGEFORUSER,
            API_GETDEFAULTOUTGOINGPHONEACCOUNT,
            API_GETDEFAULTPHONEAPP,
            API_GETLINE1NUMBER,
            API_GETOWNSELFMANAGEDPHONEACCOUNTS,
            API_GETPHONEACCOUNT,
            API_GETPHONEACCOUNTSFORPACKAGE,
            API_GETPHONEACCOUNTSSUPPORTINGSCHEME,
            API_GETREGISTEREDPHONEACCOUNTS,
            API_GETSELFMANAGEDPHONEACCOUNTS,
            API_GETSIMCALLMANAGER,
            API_GETSIMCALLMANAGERFORUSER,
            API_GETSYSTEMDIALERPACKAGE,
            API_GETUSERSELECTEDOUTGOINGPHONEACCOUNT,
            API_GETVOICEMAILNUMBER,
            API_HANDLEPINMMI,
            API_HANDLEPINMMIFORPHONEACCOUNT,
            API_HASMANAGEONGOINGCALLSPERMISSION,
            API_ISINCALL,
            API_ISINCOMINGCALLPERMITTED,
            API_ISINEMERGENCYCALL,
            API_ISINMANAGEDCALL,
            API_ISINSELFMANAGEDCALL,
            API_ISOUTGOINGCALLPERMITTED,
            API_ISRINGING,
            API_ISTTYSUPPORTED,
            API_ISVOICEMAILNUMBER,
            API_PLACECALL,
            API_REGISTERPHONEACCOUNT,
            API_SETDEFAULTDIALER,
            API_SETUSERSELECTEDOUTGOINGPHONEACCOUNT,
            API_SHOWINCALLSCREEN,
            API_SILENCERINGER,
            API_STARTCONFERENCE,
            API_UNREGISTERPHONEACCOUNT,
            API_GETCALLCONNECTEDINDICATORPREF,
            API_SETCALLCONNECTEDINDICATORPREF,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApiId {
    }

    @IntDef(prefix = "RESULT", value = {
            RESULT_UNKNOWN,
            RESULT_NORMAL,
            RESULT_PERMISSION,
            RESULT_EXCEPTION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultId {
    }

    public static class ApiEvent {

        @ApiId
        int mId;
        int mCallerUid;
        @ResultId
        int mResult;

        public ApiEvent(@ApiId int id, int callerUid, @ResultId int result) {
            mId = id;
            mCallerUid = callerUid;
            mResult = result;
        }

        public void setCallerUid(int uid) {
            this.mCallerUid = uid;
        }

        public void setResult(@ResultId int result) {
            this.mResult = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ApiEvent obj)) {
                return false;
            }
            return this.mId == obj.mId && this.mCallerUid == obj.mCallerUid
                    && this.mResult == obj.mResult;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mId, mCallerUid, mResult);
        }

        @Override
        public String toString() {
            return "[ApiEvent: mApiId=" + mId + ", mCallerUid=" + mCallerUid
                    + ", mResult=" + mResult + "]";
        }
    }
}
