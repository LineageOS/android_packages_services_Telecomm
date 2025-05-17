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

import static com.android.server.telecom.TelecomStatsLog.CALL_SEQUENCING_STATS;

import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.telecom.Log;
import android.util.StatsEvent;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.Call;
import com.android.server.telecom.TelecomStatsLog;
import com.android.server.telecom.callsequencing.CallSequencingController;
import com.android.server.telecom.nano.PulledAtomsClass;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CallSequencingStats extends TelecomPulledAtom {

    private static final String TAG = CallSequencingStats.class.getSimpleName();

    private static final String FILE_NAME = "call_sequencing_stats";

    /**
     * The following call types will be set on each call for call sequencing metrics related info.
     * The call type can either be MANAGED, SELF_MANAGED, or TRANSACTIONAL.
     */
    public static final int CALL_TYPE_UNKNOWN = 0;
    public static final int CALL_TYPE_MANAGED = 1;
    public static final int CALL_TYPE_SELF_MANAGED = 2;
    public static final int CALL_TYPE_TRANSACTIONAL = 3;

    private Map<CallSequencingStatsKey, CallSequencingStatsData> mCallSequencingStatsMap;
    private final Map<Call, CallSequencingStatsKey> mCallToStatsKeyMap = new HashMap<>();

    public CallSequencingStats(@NonNull Context context,
            @NonNull Looper looper, boolean isTestMode) {
        super(context, looper, isTestMode);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return CALL_SEQUENCING_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int onPull(List<StatsEvent> data) {
        if (mPulledAtoms.callSequencingStats.length != 0) {
            Arrays.stream(mPulledAtoms.callSequencingStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getPrimaryCallType(), v.getSecondaryCallType(),
                            v.getIsPrimaryCallEmergency(), v.getIsSecondaryCallEmergency(),
                            v.hasHasSecondary(), v.getIsSamePhoneAccount(),
                            v.getAverageDurationMs(), v.getCount())));
            mCallSequencingStatsMap.clear();
            onAggregate();
            return StatsManager.PULL_SUCCESS;
        } else {
            return StatsManager.PULL_SKIP;
        }
    }

    @Override
    protected void onLoad() {
        if (mPulledAtoms.callSequencingStats != null) {
            mCallSequencingStatsMap = new HashMap<>();
            for (PulledAtomsClass.CallSequencingStats v : mPulledAtoms.callSequencingStats) {
                mCallSequencingStatsMap.put(new CallSequencingStatsKey(v.getPrimaryCallType(),
                                v.getSecondaryCallType(), v.getIsPrimaryCallEmergency(),
                                v.getIsSecondaryCallEmergency(), v.getHasSecondary(),
                                v.getIsSamePhoneAccount()), new CallSequencingStatsData(
                                        v.getCount(), v.getAverageDurationMs()));
            }
            mLastPulledTimestamps = mPulledAtoms.getCallSequencingStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public void onAggregate() {
        Log.d(TAG, "onAggregate: %s", mCallSequencingStatsMap);
        clearAtoms();
        if (mCallSequencingStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setCallSequencingStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.callSequencingStats =
                new PulledAtomsClass.CallSequencingStats[mCallSequencingStatsMap.size()];
        int[] index = new int[1];
        mCallSequencingStatsMap.forEach((k, v) -> {
            mPulledAtoms.callSequencingStats[index[0]] = new PulledAtomsClass.CallSequencingStats();
            mPulledAtoms.callSequencingStats[index[0]].setPrimaryCallType(k.mPrimaryCallType);
            mPulledAtoms.callSequencingStats[index[0]].setSecondaryCallType(k.mSecondaryCallType);
            mPulledAtoms.callSequencingStats[index[0]]
                    .setIsPrimaryCallEmergency(k.mIsPrimaryCallEmergency);
            mPulledAtoms.callSequencingStats[index[0]].setIsSecondaryCallEmergency(
                    k.mIsSecondaryCallEmergency);
            mPulledAtoms.callSequencingStats[index[0]].setHasSecondary(k.mHasSecondary);
            mPulledAtoms.callSequencingStats[index[0]].setIsSamePhoneAccount(k.mIsSamePhoneAccount);
            mPulledAtoms.callSequencingStats[index[0]].setCount(v.mCount);
            mPulledAtoms.callSequencingStats[index[0]].setAverageDurationMs(v.mAverageDuration);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public void log(CallSequencingStatsKey key, int duration) {
        CallSequencingStatsData data = mCallSequencingStatsMap
                .computeIfAbsent(key, k -> new CallSequencingStatsData(0, 0));
        data.add(duration);
        onAggregate();
    }

    public void onCallEnd(Call call) {
        final int duration = (int) (call.getAgeMillis());
        post(() -> {
            if (mCallToStatsKeyMap.containsKey(call)) {
                log(mCallToStatsKeyMap.get(call), duration);
            }
        });
    }

    public void setCallSequencingMetrics(Call callToSet, Call primaryCall) {
        int primaryCallType, secondaryCallType = CALL_TYPE_UNKNOWN;
        boolean isPrimaryCallEmergency, isSecondaryCallEmergency = false,
                hasSecondaryCall = false, isSamePhoneAccount = false;
        // Set the primary call metrics if there's no primary call.
        if (primaryCall == null) {
            primaryCallType = getCallType(callToSet);
            isPrimaryCallEmergency = callToSet.isEmergencyCall();
        } else { // Otherwise, the call is a secondary call.
            primaryCallType = getCallType(primaryCall);
            isPrimaryCallEmergency = primaryCall.isEmergencyCall();
            secondaryCallType = getCallType(callToSet);
            isSecondaryCallEmergency = callToSet.isEmergencyCall();
            hasSecondaryCall = true;
            // Only set the following fields for secondary calls as they aren't relevant for
            // primary calls and we will duplicate data otherwise.
            isSamePhoneAccount = CallSequencingController
                    .arePhoneAccountsSame(callToSet, primaryCall);
        }
        // Store this new call information into a key which can be logged after the call ends.
        CallSequencingStatsKey key = new CallSequencingStatsKey(primaryCallType,
                secondaryCallType, isPrimaryCallEmergency, isSecondaryCallEmergency,
                hasSecondaryCall, isSamePhoneAccount);
        CallSequencingStatsData data = mCallSequencingStatsMap
                .computeIfAbsent(key, k -> new CallSequencingStatsData(0, 0));
        mCallToStatsKeyMap.putIfAbsent(callToSet, key);
    }

    @VisibleForTesting
    public int getCallType(Call call) {
        if (!call.isSelfManaged() && !call.isTransactionalCall()) {
            return CALL_TYPE_MANAGED;
        } else if (call.isSelfManaged()) {
            return CALL_TYPE_SELF_MANAGED;
        } else {
            return CALL_TYPE_TRANSACTIONAL;
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public static class CallSequencingStatsKey {
        final int mPrimaryCallType;
        final int mSecondaryCallType;
        final boolean mIsPrimaryCallEmergency;
        final boolean mIsSecondaryCallEmergency;
        final boolean mHasSecondary;
        final boolean mIsSamePhoneAccount;

        CallSequencingStatsKey(int primaryCallType,
                int secondaryCallType, boolean isPrimaryCallEmergency,
                boolean isSecondaryCallEmergency, boolean hasSecondary,
                boolean isSamePhoneAccount) {
            mPrimaryCallType = primaryCallType;
            mSecondaryCallType = secondaryCallType;
            mIsPrimaryCallEmergency = isPrimaryCallEmergency;
            mIsSecondaryCallEmergency = isSecondaryCallEmergency;
            mHasSecondary = hasSecondary;
            mIsSamePhoneAccount = isSamePhoneAccount;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CallSequencingStatsKey obj)) {
                return false;
            }
            return this.mPrimaryCallType == obj.mPrimaryCallType
                    && this.mSecondaryCallType == obj.mSecondaryCallType
                    && this.mIsPrimaryCallEmergency == obj.mIsPrimaryCallEmergency
                    && this.mIsSecondaryCallEmergency == obj.mIsSecondaryCallEmergency
                    && this.mHasSecondary == obj.mHasSecondary
                    && this.mIsSamePhoneAccount == obj.mIsSamePhoneAccount;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPrimaryCallType, mSecondaryCallType, mIsPrimaryCallEmergency,
                    mIsSecondaryCallEmergency, mHasSecondary, mIsSamePhoneAccount);
        }

        @Override
        public String toString() {
            return "[CallSequencingStatsKey: mPrimaryCallType=" + mPrimaryCallType
                    + ", mSecondaryCallType= " + mSecondaryCallType
                    + ", mIsPrimaryCallEmergency= " + mIsPrimaryCallEmergency
                    + ", mIsSecondaryCallEmergency=" + mIsSecondaryCallEmergency
                    + ", mHasSecondary= " + mHasSecondary
                    + ", mIsSamePhoneAccount= " + mIsSamePhoneAccount + "]";
        }
    }

    static class CallSequencingStatsData {

        int mCount;
        int mAverageDuration;

        CallSequencingStatsData(int count, int averageDuration) {
            mCount = count;
            mAverageDuration = averageDuration;
        }

        void add(int duration) {
            mCount++;
            mAverageDuration += (duration - mAverageDuration) / mCount;
        }

        @Override
        public String toString() {
            return "[CallSequencingStatsData: mCount=" + mCount + ", mAverageDuration:"
                    + mAverageDuration + "]";
        }
    }
}
