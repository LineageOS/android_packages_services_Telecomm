/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.telecom.callfiltering;

import android.annotation.IntDef;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.telecom.CallScreeningService;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

public class CallFilteringResult {
    /**
     * Indicates that the call filtering operation did NOT determine whether or not DND would result
     * in suppressing the call.
     */
    public static final int DND_NOT_DETERMINED = 0;

    /**
     * DND has determined that this call should be suppressed. In other words,
     * {@link android.app.NotificationManager#matchesCallFilter(Uri)} returned {@code false} and the
     * call should not ring.
     */
    public static final int DND_SUPPRESSED = 1;

    /**
     * DND has determined that this call should be allowed. This means that either DND Is off, or
     * DND is on and the call matches the criteria the user defined to allow it to bypass DND (e.g.
     * repeated caller or someone in the user's contacts).
     */
    public static final int DND_NOT_SUPPRESSED = 2;

    @IntDef(prefix = { "DND_" },
            value = {DND_NOT_SUPPRESSED, DND_SUPPRESSED, DND_NOT_DETERMINED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DndSuppressionStatus {}

    public static class Builder {
        private boolean mShouldAllowCall;
        private boolean mShouldReject;
        private boolean mShouldAddToCallLog;
        private boolean mShouldShowNotification;
        private boolean mDndSuppressed = false;
        private int mDndSuppressionStatus = DND_NOT_DETERMINED;
        private boolean mShouldSilence = false;
        private boolean mShouldScreenViaAudio = false;
        private boolean mContactExists = false;
        private int mCallBlockReason = Calls.BLOCK_REASON_NOT_BLOCKED;
        private CharSequence mCallScreeningAppName = null;
        private String mCallScreeningComponentName = null;
        private CallScreeningService.ParcelableCallResponse mCallScreeningResponse = null;
        private boolean mIsResponseFromSystemDialer = false;

        public Builder setShouldAllowCall(boolean shouldAllowCall) {
            mShouldAllowCall = shouldAllowCall;
            return this;
        }

        public Builder setShouldReject(boolean shouldReject) {
            mShouldReject = shouldReject;
            return this;
        }

        public Builder setShouldAddToCallLog(boolean shouldAddToCallLog) {
            mShouldAddToCallLog = shouldAddToCallLog;
            return this;
        }

        public Builder setShouldShowNotification(boolean shouldShowNotification) {
            mShouldShowNotification = shouldShowNotification;
            return this;
        }

        public Builder setDndSuppressed(boolean shouldPerformCheck) {
            mDndSuppressed = shouldPerformCheck;
            return this;
        }

        /**
         * Sets whether the call should bypass DND or not.
         * @param status the DND suppression status.
         * @return the builder to allow chaining.
         */
        public Builder setDndSuppressionStatus(@DndSuppressionStatus int status) {
            mDndSuppressionStatus = status;
            return this;
        }

        public Builder setShouldSilence(boolean shouldSilence) {
            mShouldSilence = shouldSilence;
            return this;
        }

        public Builder setCallBlockReason(int callBlockReason) {
            mCallBlockReason = callBlockReason;
            return this;
        }

        public Builder setShouldScreenViaAudio(boolean shouldScreenViaAudio) {
            mShouldScreenViaAudio = shouldScreenViaAudio;
            return this;
        }

        public Builder setCallScreeningAppName(CharSequence callScreeningAppName) {
            mCallScreeningAppName = callScreeningAppName;
            return this;
        }

        public Builder setCallScreeningComponentName(String callScreeningComponentName) {
            mCallScreeningComponentName = callScreeningComponentName;
            return this;
        }

        public Builder setCallScreeningResponse(
                CallScreeningService.ParcelableCallResponse response, boolean isFromSystemDialer) {
            mCallScreeningResponse = response;
            mIsResponseFromSystemDialer = isFromSystemDialer;
            return this;
        }

        public Builder setContactExists(boolean contactExists) {
            mContactExists = contactExists;
            return this;
        }

        public static Builder from(CallFilteringResult result) {
            return new Builder()
                    .setShouldAllowCall(result.shouldAllowCall)
                    .setShouldReject(result.shouldReject)
                    .setShouldAddToCallLog(result.shouldAddToCallLog)
                    .setShouldShowNotification(result.shouldShowNotification)
                    .setDndSuppressed(result.shouldSuppressCallDueToDndStatus)
                    .setDndSuppressionStatus(result.dndSuppressionStatus)
                    .setShouldSilence(result.shouldSilence)
                    .setCallBlockReason(result.mCallBlockReason)
                    .setShouldScreenViaAudio(result.shouldScreenViaAudio)
                    .setCallScreeningAppName(result.mCallScreeningAppName)
                    .setCallScreeningComponentName(result.mCallScreeningComponentName)
                    .setCallScreeningResponse(result.mCallScreeningResponse,
                            result.mIsResponseFromSystemDialer)
                    .setContactExists(result.contactExists);
        }

        public CallFilteringResult build() {
            return new CallFilteringResult(mShouldAllowCall, mShouldReject, mShouldSilence,
                    mShouldAddToCallLog, mShouldShowNotification,
                    mDndSuppressed, mDndSuppressionStatus, mCallBlockReason, mCallScreeningAppName,
                    mCallScreeningComponentName, mCallScreeningResponse,
                    mIsResponseFromSystemDialer, mShouldScreenViaAudio, mContactExists);
        }
    }

    public boolean shouldAllowCall;
    public boolean shouldReject;
    public boolean shouldSilence;
    public boolean shouldAddToCallLog;
    public boolean shouldScreenViaAudio = false;
    public boolean shouldShowNotification;
    public boolean shouldSuppressCallDueToDndStatus = false;
    @DndSuppressionStatus public int dndSuppressionStatus = DND_NOT_DETERMINED;
    public int mCallBlockReason;
    public CharSequence mCallScreeningAppName;
    public String mCallScreeningComponentName;
    public CallScreeningService.ParcelableCallResponse mCallScreeningResponse;
    public boolean mIsResponseFromSystemDialer;
    public boolean contactExists;

    private CallFilteringResult(boolean shouldAllowCall, boolean shouldReject, boolean
            shouldSilence, boolean shouldAddToCallLog, boolean shouldShowNotification, boolean
            shouldSuppress, @DndSuppressionStatus int dndSuppressionStatus, int callBlockReason,
            CharSequence callScreeningAppName, String callScreeningComponentName,
            CallScreeningService.ParcelableCallResponse callScreeningResponse,
            boolean isResponseFromSystemDialer,
            boolean shouldScreenViaAudio, boolean contactExists) {
        this.shouldAllowCall = shouldAllowCall;
        this.shouldReject = shouldReject;
        this.shouldSilence = shouldSilence;
        this.shouldAddToCallLog = shouldAddToCallLog;
        this.shouldShowNotification = shouldShowNotification;
        this.shouldSuppressCallDueToDndStatus = shouldSuppress;
        this.dndSuppressionStatus = dndSuppressionStatus;
        this.shouldScreenViaAudio = shouldScreenViaAudio;
        this.mCallBlockReason = callBlockReason;
        this.mCallScreeningAppName = callScreeningAppName;
        this.mCallScreeningComponentName = callScreeningComponentName;
        this.mCallScreeningResponse = callScreeningResponse;
        this.mIsResponseFromSystemDialer = isResponseFromSystemDialer;
        this.contactExists = contactExists;
    }

    /**
     * Combine this CallFilteringResult with another, returning a CallFilteringResult with the more
     * restrictive properties of the two. Where there are multiple call filtering components which
     * block a call, the first filter from {@link BlockCheckerFilter},
     * {@link DirectToVoicemailFilter}, {@link CallScreeningServiceFilter} which blocked a call
     * shall be used to populate the call block reason, component name, etc.
     */
    public CallFilteringResult combine(CallFilteringResult other) {
        if (other == null) {
            return this;
        }

        if (isBlockedByProvider(mCallBlockReason)) {
            return getCombinedCallFilteringResult(other, mCallBlockReason,
                    null /*callScreeningAppName*/, null /*callScreeningComponentName*/);
        } else if (isBlockedByProvider(other.mCallBlockReason)) {
            return getCombinedCallFilteringResult(other, other.mCallBlockReason,
                    null /*callScreeningAppName*/, null /*callScreeningComponentName*/);
        }

        if (mCallBlockReason == Calls.BLOCK_REASON_DIRECT_TO_VOICEMAIL
                || other.mCallBlockReason == Calls.BLOCK_REASON_DIRECT_TO_VOICEMAIL) {
            return getCombinedCallFilteringResult(other, Calls.BLOCK_REASON_DIRECT_TO_VOICEMAIL,
                    null /*callScreeningAppName*/, null /*callScreeningComponentName*/);
        }

        if (shouldReject && mCallBlockReason == CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE) {
            return getCombinedCallFilteringResult(other, Calls.BLOCK_REASON_CALL_SCREENING_SERVICE,
                    mCallScreeningAppName, mCallScreeningComponentName);
        } else if (other.shouldReject && other.mCallBlockReason == CallLog.Calls
                .BLOCK_REASON_CALL_SCREENING_SERVICE) {
            return getCombinedCallFilteringResult(other, Calls.BLOCK_REASON_CALL_SCREENING_SERVICE,
                    other.mCallScreeningAppName, other.mCallScreeningComponentName);
        }

        if (shouldScreenViaAudio) {
            return getCombinedCallFilteringResult(other, Calls.BLOCK_REASON_NOT_BLOCKED,
                    mCallScreeningAppName, mCallScreeningComponentName);
        } else if (other.shouldScreenViaAudio) {
            return getCombinedCallFilteringResult(other, Calls.BLOCK_REASON_NOT_BLOCKED,
                    other.mCallScreeningAppName, other.mCallScreeningComponentName);
        }

        Builder b = new Builder()
                .setShouldAllowCall(shouldAllowCall && other.shouldAllowCall)
                .setShouldReject(shouldReject || other.shouldReject)
                .setShouldSilence(shouldSilence || other.shouldSilence)
                .setShouldAddToCallLog(shouldAddToCallLog && other.shouldAddToCallLog)
                .setShouldShowNotification(shouldShowNotification && other.shouldShowNotification)
                .setShouldScreenViaAudio(shouldScreenViaAudio || other.shouldScreenViaAudio)
                .setDndSuppressed(shouldSuppressCallDueToDndStatus
                        || other.shouldSuppressCallDueToDndStatus)
                .setDndSuppressionStatus(getCombinedDndSuppressionStatus(dndSuppressionStatus,
                        other.dndSuppressionStatus))
                .setContactExists(contactExists || other.contactExists);
        combineScreeningResponses(b, this, other);
        return b.build();
    }

    /**
     * Determines if a DND status has been determined.
     * @return {@code true} if the filtering result has a DND suppression status, {@code false}
     * otherwise.
     */
    public boolean isDndSuppressionDetermined() {
        return dndSuppressionStatus != DND_NOT_DETERMINED;
    }

    /**
     * Determines if the call should be suppressed due to DND.
     * @return {@code true} if the call should be suppressed due to DND, {@code false} otherwise.
     */
    public boolean shouldSuppressDueToDnd() {
        return dndSuppressionStatus == DND_SUPPRESSED;
    }

    /** Combine two DND suppression statuses. */
    public static int getCombinedDndSuppressionStatus(@DndSuppressionStatus int suppressionStatus,
            @DndSuppressionStatus int otherSuppressionStatus) {
        int combinedDndSuppressionStatus;
        // Suppression status combination rules in priority order:
        // 1. If one of the filters says DND suppressed the call (even if the other didn't), we
        // mark as suppressed.
        // 2. If one of the filters says DND did NOT suppress the call (even if the other didn't
        // make a determination), we mark as not-suppressed.
        // 3. Fall back to DND_NOT_DETERMINED.
        if (suppressionStatus == DND_SUPPRESSED || otherSuppressionStatus == DND_SUPPRESSED) {
            combinedDndSuppressionStatus = DND_SUPPRESSED;
        } else if (suppressionStatus == DND_NOT_SUPPRESSED
                || otherSuppressionStatus == DND_NOT_SUPPRESSED) {
            combinedDndSuppressionStatus = DND_NOT_SUPPRESSED;
        } else {
            combinedDndSuppressionStatus = DND_NOT_DETERMINED;
        }
        return combinedDndSuppressionStatus;
    }

    private boolean isBlockedByProvider(int blockReason) {
        if (blockReason == Calls.BLOCK_REASON_BLOCKED_NUMBER
            || blockReason == Calls.BLOCK_REASON_UNKNOWN_NUMBER
            || blockReason == Calls.BLOCK_REASON_RESTRICTED_NUMBER
            || blockReason == Calls.BLOCK_REASON_PAY_PHONE
            || blockReason == Calls.BLOCK_REASON_NOT_IN_CONTACTS) {
            return true;
        }

        return false;
    }

    private CallFilteringResult getCombinedCallFilteringResult(CallFilteringResult other,
            int callBlockReason, CharSequence callScreeningAppName,
            String callScreeningComponentName) {
        Builder b = new Builder()
                .setShouldAllowCall(shouldAllowCall && other.shouldAllowCall)
                .setShouldReject(shouldReject || other.shouldReject)
                .setShouldSilence(shouldSilence || other.shouldSilence)
                .setShouldAddToCallLog(shouldAddToCallLog && other.shouldAddToCallLog)
                .setShouldShowNotification(shouldShowNotification && other.shouldShowNotification)
                .setDndSuppressed(shouldSuppressCallDueToDndStatus
                        || other.shouldSuppressCallDueToDndStatus)
                .setDndSuppressionStatus(getCombinedDndSuppressionStatus(dndSuppressionStatus,
                        other.dndSuppressionStatus))
                .setShouldScreenViaAudio(shouldScreenViaAudio || other.shouldScreenViaAudio)
                .setCallBlockReason(callBlockReason)
                .setCallScreeningAppName(callScreeningAppName)
                .setCallScreeningComponentName(callScreeningComponentName)
                .setContactExists(contactExists || other.contactExists);
        combineScreeningResponses(b, this, other);
        return b.build();
    }

    private static void combineScreeningResponses(Builder builder, CallFilteringResult r1,
            CallFilteringResult r2) {
        if (r1.mIsResponseFromSystemDialer) {
            builder.setCallScreeningResponse(r1.mCallScreeningResponse, true);
            builder.setCallScreeningComponentName(r1.mCallScreeningComponentName);
            builder.setCallScreeningAppName(r1.mCallScreeningAppName);
        } else if (r2.mIsResponseFromSystemDialer) {
            builder.setCallScreeningResponse(r2.mCallScreeningResponse, true);
            builder.setCallScreeningComponentName(r2.mCallScreeningComponentName);
            builder.setCallScreeningAppName(r2.mCallScreeningAppName);
        } else {
            if (r1.mCallScreeningResponse != null) {
                builder.setCallScreeningResponse(r1.mCallScreeningResponse, false);
                builder.setCallScreeningComponentName(r1.mCallScreeningComponentName);
                builder.setCallScreeningAppName(r1.mCallScreeningAppName);
            } else {
                builder.setCallScreeningResponse(r2.mCallScreeningResponse, false);
                builder.setCallScreeningComponentName(r2.mCallScreeningComponentName);
                builder.setCallScreeningAppName(r2.mCallScreeningAppName);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CallFilteringResult that = (CallFilteringResult) o;

        if (shouldAllowCall != that.shouldAllowCall) return false;
        if (shouldReject != that.shouldReject) return false;
        if (shouldSilence != that.shouldSilence) return false;
        if (shouldAddToCallLog != that.shouldAddToCallLog) return false;
        if (shouldShowNotification != that.shouldShowNotification) return false;
        if (shouldSuppressCallDueToDndStatus != that.shouldSuppressCallDueToDndStatus) return false;
        if (mCallBlockReason != that.mCallBlockReason) return false;
        if (contactExists != that.contactExists) return false;

        if (!Objects.equals(mCallScreeningAppName, that.mCallScreeningAppName)) return false;
        if (!Objects.equals(mCallScreeningComponentName, that.mCallScreeningComponentName)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = (shouldAllowCall ? 1 : 0);
        result = 31 * result + (shouldReject ? 1 : 0);
        result = 31 * result + (shouldSilence ? 1 : 0);
        result = 31 * result + (shouldAddToCallLog ? 1 : 0);
        result = 31 * result + (shouldShowNotification ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        if (shouldAllowCall) {
            sb.append("Allow");
        } else if (shouldReject) {
            sb.append("Reject");
        } else if (shouldSilence) {
            sb.append("Silence");
        } else {
            sb.append("Ignore");
        }

        if (shouldScreenViaAudio) {
            sb.append(", audio processing");
        }

        if (shouldAddToCallLog) {
            sb.append(", logged");
        }

        if (shouldShowNotification) {
            sb.append(", notified");
        }

        if (shouldSuppressCallDueToDndStatus) {
            sb.append(", DND suppressed");
        }

        if (dndSuppressionStatus == DND_NOT_DETERMINED) {
            sb.append(", DND not determined");
        } else if (dndSuppressionStatus == DND_SUPPRESSED) {
            sb.append(", DND suppressed");
        } else {
            sb.append(", DND not suppressed");
        }

        if (contactExists) {
            sb.append(", contact exists");
        }

        if (mCallBlockReason != 0) {
            sb.append(", mCallBlockReason = ");
            sb.append(mCallBlockReason);
        }

        if (!TextUtils.isEmpty(mCallScreeningAppName)) {
            sb.append(", mCallScreeningAppName = ");
            sb.append(mCallScreeningAppName);
        }

        if (!TextUtils.isEmpty(mCallScreeningComponentName)) {
            sb.append(", mCallScreeningComponentName = ");
            sb.append(mCallScreeningComponentName);
        }
        sb.append("]");

        return sb.toString();
    }
}
