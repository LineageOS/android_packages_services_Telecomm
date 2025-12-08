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

import static com.android.server.telecom.TelecomStatsLog.TELECOM_ERROR_STATS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.telecom.CallException;
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

public class ErrorStats extends TelecomPulledAtom {
    public static final int SUB_UNKNOWN = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_UNKNOWN;
    public static final int SUB_CALL_AUDIO = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_CALL_AUDIO;
    public static final int SUB_CALL_LOGS = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_CALL_LOGS;
    public static final int SUB_CALL_MANAGER = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_CALL_MANAGER;
    public static final int SUB_CONNECTION_SERVICE = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_CONNECTION_SERVICE;
    public static final int SUB_EMERGENCY_CALL = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_EMERGENCY_CALL;
    public static final int SUB_IN_CALL_SERVICE = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_IN_CALL_SERVICE;
    public static final int SUB_MISC = TelecomStatsLog.TELECOM_ERROR_STATS__SUBMODULE__SUB_MISC;
    public static final int SUB_PHONE_ACCOUNT = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_PHONE_ACCOUNT;
    public static final int SUB_SYSTEM_SERVICE = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_SYSTEM_SERVICE;
    public static final int SUB_TELEPHONY = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_TELEPHONY;
    public static final int SUB_UI = TelecomStatsLog.TELECOM_ERROR_STATS__SUBMODULE__SUB_UI;
    public static final int SUB_VOIP_CALL = TelecomStatsLog
            .TELECOM_ERROR_STATS__SUBMODULE__SUB_VOIP_CALL;
    public static final int ERROR_UNKNOWN = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_UNKNOWN;
    public static final int ERROR_EXTERNAL_EXCEPTION = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_EXTERNAL_EXCEPTION;
    public static final int ERROR_INTERNAL_EXCEPTION = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_INTERNAL_EXCEPTION;
    public static final int ERROR_AUDIO_ROUTE_RETRY_REJECTED = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_AUDIO_ROUTE_RETRY_REJECTED;
    public static final int ERROR_BT_GET_SERVICE_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_BT_GET_SERVICE_FAILURE;
    public static final int ERROR_BT_REGISTER_CALLBACK_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_BT_REGISTER_CALLBACK_FAILURE;
    public static final int ERROR_AUDIO_ROUTE_UNAVAILABLE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_AUDIO_ROUTE_UNAVAILABLE;
    public static final int ERROR_EMERGENCY_NUMBER_DETERMINED_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_EMERGENCY_NUMBER_DETERMINED_FAILURE;
    public static final int ERROR_NOTIFY_CALL_STREAM_START_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_NOTIFY_CALL_STREAM_START_FAILURE;
    public static final int ERROR_NOTIFY_CALL_STREAM_STATE_CHANGED_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_NOTIFY_CALL_STREAM_STATE_CHANGED_FAILURE;
    public static final int ERROR_NOTIFY_CALL_STREAM_STOP_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_NOTIFY_CALL_STREAM_STOP_FAILURE;
    public static final int ERROR_RTT_STREAM_CLOSE_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_RTT_STREAM_CLOSE_FAILURE;
    public static final int ERROR_RTT_STREAM_CREATE_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_RTT_STREAM_CREATE_FAILURE;
    public static final int ERROR_SET_MUTED_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_SET_MUTED_FAILURE;
    public static final int ERROR_VIDEO_PROVIDER_SET_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_VIDEO_PROVIDER_SET_FAILURE;
    public static final int ERROR_WIRED_HEADSET_NOT_AVAILABLE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_WIRED_HEADSET_NOT_AVAILABLE;
    public static final int ERROR_LOG_CALL_FAILURE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_LOG_CALL_FAILURE;
    public static final int ERROR_RETRIEVING_ACCOUNT_EMERGENCY = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_RETRIEVING_ACCOUNT_EMERGENCY;
    public static final int ERROR_RETRIEVING_ACCOUNT = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_RETRIEVING_ACCOUNT;
    public static final int ERROR_EMERGENCY_CALL_ABORTED_NO_ACCOUNT = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_EMERGENCY_CALL_ABORTED_NO_ACCOUNT;
    public static final int ERROR_DEFAULT_MO_ACCOUNT_MISMATCH = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_DEFAULT_MO_ACCOUNT_MISMATCH;
    public static final int ERROR_ESTABLISHING_CONNECTION = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_ESTABLISHING_CONNECTION;
    public static final int ERROR_REMOVING_CALL = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_REMOVING_CALL;
    public static final int ERROR_STUCK_CONNECTING_EMERGENCY = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_STUCK_CONNECTING_EMERGENCY;
    public static final int ERROR_STUCK_CONNECTING = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_STUCK_CONNECTING;
    // Errors from android.telecom.CallException
    public static final int ERROR_TRANSACTION_UNKNOWN = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_TRANSACTION_UNKNOWN;
    public static final int ERROR_CANNOT_HOLD_CALL = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_CANNOT_HOLD_CALL;
    public static final int ERROR_CALL_NOT_TRACKED = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_CALL_NOT_TRACKED;
    public static final int ERROR_CANNOT_SET_ACTIVE = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_CANNOT_SET_ACTIVE;
    public static final int ERROR_CALL_NOT_PERMITTED = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_CALL_NOT_PERMITTED;
    public static final int ERROR_OPERATION_TIMED_OUT = TelecomStatsLog
            .TELECOM_ERROR_STATS__ERROR__ERROR_OPERATION_TIMED_OUT;
    private static final String TAG = ErrorStats.class.getSimpleName();
    private static final String FILE_NAME = "error_stats";
    private Map<ErrorEvent, Integer> mErrorStatsMap;

    public ErrorStats(@NonNull Context context, @NonNull Looper looper, boolean isTestMode) {
        super(context, looper, isTestMode);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return TELECOM_ERROR_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.telecomErrorStats.length != 0) {
            Arrays.stream(mPulledAtoms.telecomErrorStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getSubmodule(), v.getError(), v.getCount())));
            mErrorStatsMap.clear();
            onAggregate();
        }
        return StatsManager.PULL_SUCCESS;
    }

    @Override
    protected synchronized void onLoad() {
        if (mPulledAtoms.telecomErrorStats != null) {
            mErrorStatsMap = new HashMap<>();
            for (PulledAtomsClass.TelecomErrorStats v : mPulledAtoms.telecomErrorStats) {
                mErrorStatsMap.put(new ErrorEvent(v.getSubmodule(), v.getError()),
                        v.getCount());
            }
            mLastPulledTimestamps = mPulledAtoms.getTelecomErrorStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        Log.d(TAG, "onAggregate: %s", mErrorStatsMap);
        clearAtoms();
        if (mErrorStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setTelecomErrorStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.telecomErrorStats =
                new PulledAtomsClass.TelecomErrorStats[mErrorStatsMap.size()];
        int[] index = new int[1];
        mErrorStatsMap.forEach((k, v) -> {
            mPulledAtoms.telecomErrorStats[index[0]] = new PulledAtomsClass.TelecomErrorStats();
            mPulledAtoms.telecomErrorStats[index[0]].setSubmodule(k.mModuleId);
            mPulledAtoms.telecomErrorStats[index[0]].setError(k.mErrorId);
            mPulledAtoms.telecomErrorStats[index[0]].setCount(v);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    public void log(@SubModuleId int moduleId, @ErrorId int errorId) {
        post(() -> {
            ErrorEvent key = new ErrorEvent(moduleId, errorId);
            mErrorStatsMap.put(key, mErrorStatsMap.getOrDefault(key, 0) + 1);
            onAggregate();
        });
    }

    @IntDef(prefix = "SUB", value = {
            SUB_UNKNOWN,
            SUB_CALL_AUDIO,
            SUB_CALL_LOGS,
            SUB_CALL_MANAGER,
            SUB_CONNECTION_SERVICE,
            SUB_EMERGENCY_CALL,
            SUB_IN_CALL_SERVICE,
            SUB_MISC,
            SUB_PHONE_ACCOUNT,
            SUB_SYSTEM_SERVICE,
            SUB_TELEPHONY,
            SUB_UI,
            SUB_VOIP_CALL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SubModuleId {
    }

    @IntDef(prefix = "ERROR", value = {
            ERROR_UNKNOWN,
            ERROR_EXTERNAL_EXCEPTION,
            ERROR_INTERNAL_EXCEPTION,
            ERROR_AUDIO_ROUTE_RETRY_REJECTED,
            ERROR_BT_GET_SERVICE_FAILURE,
            ERROR_BT_REGISTER_CALLBACK_FAILURE,
            ERROR_AUDIO_ROUTE_UNAVAILABLE,
            ERROR_EMERGENCY_NUMBER_DETERMINED_FAILURE,
            ERROR_NOTIFY_CALL_STREAM_START_FAILURE,
            ERROR_NOTIFY_CALL_STREAM_STATE_CHANGED_FAILURE,
            ERROR_NOTIFY_CALL_STREAM_STOP_FAILURE,
            ERROR_RTT_STREAM_CLOSE_FAILURE,
            ERROR_RTT_STREAM_CREATE_FAILURE,
            ERROR_SET_MUTED_FAILURE,
            ERROR_VIDEO_PROVIDER_SET_FAILURE,
            ERROR_WIRED_HEADSET_NOT_AVAILABLE,
            ERROR_LOG_CALL_FAILURE,
            ERROR_RETRIEVING_ACCOUNT_EMERGENCY,
            ERROR_RETRIEVING_ACCOUNT,
            ERROR_EMERGENCY_CALL_ABORTED_NO_ACCOUNT,
            ERROR_DEFAULT_MO_ACCOUNT_MISMATCH,
            ERROR_ESTABLISHING_CONNECTION,
            ERROR_REMOVING_CALL,
            ERROR_STUCK_CONNECTING_EMERGENCY,
            ERROR_STUCK_CONNECTING,
            /* CallException errors*/
            ERROR_TRANSACTION_UNKNOWN,
            ERROR_CANNOT_HOLD_CALL,
            ERROR_CALL_NOT_TRACKED,
            ERROR_CANNOT_SET_ACTIVE,
            ERROR_CALL_NOT_PERMITTED,
            ERROR_OPERATION_TIMED_OUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ErrorId {
    }

    static class ErrorEvent {

        final @SubModuleId int mModuleId;
        final @ErrorId int mErrorId;

        ErrorEvent(@SubModuleId int moduleId, @ErrorId int errorId) {
            mModuleId = moduleId;
            mErrorId = errorId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ErrorEvent obj)) {
                return false;
            }
            return this.mModuleId == obj.mModuleId && this.mErrorId == obj.mErrorId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mModuleId, mErrorId);
        }

        @Override
        public String toString() {
            return "[ErrorEvent: mModuleId=" + mModuleId + ", mErrorId=" + mErrorId + "]";
        }
    }

    /**
     * Maps a {@link CallException.CallErrorCode} to its corresponding {@link ErrorId}.
     *
     * @param exceptionCode The error code from a {@link CallException}.
     * @return The matching {@link ErrorId} for logging in stats.
     */
    public static @ErrorStats.ErrorId int mapCallExceptionToErrorId(
            @CallException.CallErrorCode int exceptionCode) {
        switch (exceptionCode) {
            case CallException.CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL:
                return ErrorStats.ERROR_CANNOT_HOLD_CALL;
            case CallException.CODE_CALL_IS_NOT_BEING_TRACKED:
                return ErrorStats.ERROR_CALL_NOT_TRACKED;
            case CallException.CODE_CALL_CANNOT_BE_SET_TO_ACTIVE:
                return ErrorStats.ERROR_CANNOT_SET_ACTIVE;
            case CallException.CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME:
                return ErrorStats.ERROR_CALL_NOT_PERMITTED;
            case CallException.CODE_OPERATION_TIMED_OUT:
                return ErrorStats.ERROR_OPERATION_TIMED_OUT;
            case CallException.CODE_ERROR_UNKNOWN:
            default:
                return ErrorStats.ERROR_TRANSACTION_UNKNOWN;
        }
    }
}
