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

package com.android.server.telecom.metrics;

import static com.android.server.telecom.TelecomStatsLog.TELECOM_EVENT_STATS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.telecom.CallException;
import android.telecom.Log;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallLogManager;
import com.android.server.telecom.TelecomStatsLog;
import com.android.server.telecom.metrics.ApiStats.ApiEvent;
import com.android.server.telecom.nano.PulledAtomsClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class EventStats extends TelecomPulledAtom {
    public static final int ID_UNKNOWN = TelecomStatsLog.TELECOM_EVENT_STATS__EVENT__EVENT_UNKNOWN;
    public static final int ID_INIT = TelecomStatsLog.TELECOM_EVENT_STATS__EVENT__EVENT_INIT;
    public static final int ID_DEFAULT_DIALER_CHANGED = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT__EVENT_DEFAULT_DIALER_CHANGED;
    public static final int ID_ADD_CALL = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT__EVENT_ADD_CALL;
    public static final int ID_LOG_VOIP_CALL_INCOMING_VOICE = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT__EVENT_LOG_VOIP_CALL_INCOMING_VOICE;
    public static final int ID_LOG_VOIP_CALL_INCOMING_VIDEO = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT__EVENT_LOG_VOIP_CALL_INCOMING_VIDEO;
    public static final int ID_LOG_VOIP_CALL_OUTGOING_VOICE = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT__EVENT_LOG_VOIP_CALL_OUTGOING_VOICE;
    public static final int ID_LOG_VOIP_CALL_OUTGOING_VIDEO = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT__EVENT_LOG_VOIP_CALL_OUTGOING_VIDEO;
    public static final int ID_CALL_BACK = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT__EVENT_CALL_BACK;

    public static final int CAUSE_UNKNOWN = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT_CAUSE__CAUSE_UNKNOWN;
    public static final int CAUSE_GENERIC_SUCCESS = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT_CAUSE__CAUSE_GENERIC_SUCCESS;
    public static final int CAUSE_GENERIC_FAILURE = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT_CAUSE__CAUSE_GENERIC_FAILURE;
    public static final int CAUSE_CALL_TRANSACTION_SUCCESS = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT_CAUSE__CALL_TRANSACTION_SUCCESS;
    public static final int CAUSE_CALL_TRANSACTION_BASE = CAUSE_CALL_TRANSACTION_SUCCESS;
    public static final int CAUSE_CALL_TRANSACTION_ERROR_UNKNOWN =
            CAUSE_CALL_TRANSACTION_BASE + CallException.CODE_ERROR_UNKNOWN;
    public static final int CAUSE_CALL_TRANSACTION_CANNOT_HOLD_CURRENT_ACTIVE_CALL =
            CAUSE_CALL_TRANSACTION_BASE + CallException.CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL;
    public static final int CAUSE_CALL_TRANSACTION_CALL_IS_NOT_BEING_TRACKED =
            CAUSE_CALL_TRANSACTION_BASE + CallException.CODE_CALL_IS_NOT_BEING_TRACKED;
    public static final int CAUSE_CALL_TRANSACTION_CALL_CANNOT_BE_SET_TO_ACTIVE =
            CAUSE_CALL_TRANSACTION_BASE + CallException.CODE_CALL_CANNOT_BE_SET_TO_ACTIVE;
    public static final int CAUSE_CALL_TRANSACTION_CALL_NOT_PERMITTED_AT_PRESENT_TIME =
            CAUSE_CALL_TRANSACTION_BASE + CallException.CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME;
    public static final int CAUSE_CALL_TRANSACTION_OPERATION_TIMED_OUT =
            CAUSE_CALL_TRANSACTION_BASE + CallException.CODE_OPERATION_TIMED_OUT;
    public static final int CAUSE_CALL_LOG_OPT_OUT = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT_CAUSE__CALL_LOG_OPT_OUT;
    public static final int CAUSE_CALL_LOG_OPT_IN = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT_CAUSE__CALL_LOG_OPT_IN;
    public static final int CAUSE_CALL_LOG_OPT_IN_EXCLUDED = TelecomStatsLog
            .TELECOM_EVENT_STATS__EVENT_CAUSE__CALL_LOG_OPT_IN_EXCLUDED;

    private static final String TAG = EventStats.class.getSimpleName();
    private static final String FILE_NAME = "event_stats";
    private Map<CriticalEvent, Integer> mEventStatsMap;

    public EventStats(@NonNull Context context, @NonNull Looper looper,
                      boolean isTestMode) {
        super(context, looper, isTestMode);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return TELECOM_EVENT_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.telecomEventStats.length != 0) {
            Arrays.stream(mPulledAtoms.telecomEventStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getEvent(), v.getUid(), v.getEventCause(), v.getCount())));
            mEventStatsMap.clear();
            onAggregate();
        }
        return StatsManager.PULL_SUCCESS;
    }

    @Override
    protected synchronized void onLoad() {
        if (mPulledAtoms.telecomEventStats != null) {
            mEventStatsMap = new HashMap<>();
            for (PulledAtomsClass.TelecomEventStats v : mPulledAtoms.telecomEventStats) {
                mEventStatsMap.put(new CriticalEvent(v.getEvent(), v.getUid(),
                        v.getEventCause()), v.getCount());
            }
            mLastPulledTimestamps = mPulledAtoms.getTelecomEventStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        Log.d(TAG, "onAggregate: %s", mEventStatsMap);
        clearAtoms();
        if (mEventStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setTelecomEventStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.telecomEventStats =
                new PulledAtomsClass.TelecomEventStats[mEventStatsMap.size()];
        int[] index = new int[1];
        mEventStatsMap.forEach((k, v) -> {
            mPulledAtoms.telecomEventStats[index[0]] = new PulledAtomsClass.TelecomEventStats();
            mPulledAtoms.telecomEventStats[index[0]].setEvent(k.mId);
            mPulledAtoms.telecomEventStats[index[0]].setUid(k.mUid);
            mPulledAtoms.telecomEventStats[index[0]].setEventCause(k.mCause);
            mPulledAtoms.telecomEventStats[index[0]].setCount(v);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    public void log(@NonNull CriticalEvent event) {
        post(() -> {
            mEventStatsMap.put(event, mEventStatsMap.getOrDefault(event, 0) + 1);
            onAggregate();
        });
    }

    public void onCallEnd(@NonNull Call call) {
        int id = call.isIncoming() ? (call.hasVideoCall() ? ID_LOG_VOIP_CALL_INCOMING_VIDEO
                : ID_LOG_VOIP_CALL_INCOMING_VOICE) : (
                        call.hasVideoCall() ? ID_LOG_VOIP_CALL_OUTGOING_VIDEO
                        : ID_LOG_VOIP_CALL_OUTGOING_VOICE);
        int uid = call.getCallingPackageIdentity().mCallingPackageUid;
        try {
            uid = mContext.getPackageManager().getApplicationInfo(
                    call.getTargetPhoneAccount().getComponentName().getPackageName(), 0).uid;
        } catch (Exception e) {
            Log.i(TAG, "failed to get the uid for " + e);
        }
        int cause = CallLogManager.shouldLogVoipCall(call) ? CAUSE_CALL_LOG_OPT_IN
                : call.isTransactionalLogExcluded() ? CAUSE_CALL_LOG_OPT_IN_EXCLUDED
                        : CAUSE_CALL_LOG_OPT_OUT;
        log(new CriticalEvent(id, uid, cause));

    }

    @IntDef(prefix = "ID_", value = {
            ID_UNKNOWN,
            ID_INIT,
            ID_DEFAULT_DIALER_CHANGED,
            ID_ADD_CALL,
            ID_LOG_VOIP_CALL_INCOMING_VOICE,
            ID_LOG_VOIP_CALL_INCOMING_VIDEO,
            ID_LOG_VOIP_CALL_OUTGOING_VOICE,
            ID_LOG_VOIP_CALL_OUTGOING_VIDEO,
            ID_CALL_BACK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventId {
    }

    @IntDef(prefix = "CAUSE_", value = {
            CAUSE_UNKNOWN,
            CAUSE_GENERIC_SUCCESS,
            CAUSE_GENERIC_FAILURE,
            CAUSE_CALL_TRANSACTION_SUCCESS,
            CAUSE_CALL_TRANSACTION_ERROR_UNKNOWN,
            CAUSE_CALL_TRANSACTION_CANNOT_HOLD_CURRENT_ACTIVE_CALL,
            CAUSE_CALL_TRANSACTION_CALL_IS_NOT_BEING_TRACKED,
            CAUSE_CALL_TRANSACTION_CALL_CANNOT_BE_SET_TO_ACTIVE,
            CAUSE_CALL_TRANSACTION_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
            CAUSE_CALL_TRANSACTION_OPERATION_TIMED_OUT,
            CAUSE_CALL_LOG_OPT_OUT,
            CAUSE_CALL_LOG_OPT_IN,
            CAUSE_CALL_LOG_OPT_IN_EXCLUDED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CauseId {
    }

    public static class CriticalEvent {

        @EventId
        int mId;
        int mUid;
        @CauseId
        int mCause;

        public CriticalEvent(@EventId int id, int uid, @CauseId int cause) {
            mId = id;
            mUid = uid;
            mCause = cause;
        }

        public void setUid(int uid) {
            this.mUid = uid;
        }

        public void setResult(@CauseId int result) {
            this.mCause = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ApiEvent obj)) {
                return false;
            }
            return this.mId == obj.mId && this.mUid == obj.mCallerUid
                    && this.mCause == obj.mResult;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mId, mUid, mCause);
        }

        @Override
        public String toString() {
            return "[CriticalEvent: mId=" + mId + ", m"
                    + "Uid=" + mUid
                    + ", mResult=" + mCause + "]";
        }
    }


}
