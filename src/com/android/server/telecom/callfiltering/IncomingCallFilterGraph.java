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
 * limitations under the License.
 */

package com.android.server.telecom.callfiltering;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.telecom.Log;

import com.android.server.telecom.Call;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class IncomingCallFilterGraph {
    //TODO: Add logging for control flow.
    public static final String TAG = "IncomingCallFilterGraph";
    public static final CallFilteringResult DEFAULT_SCREENING_RESULT =
            new CallFilteringResult.Builder()
                    .setShouldAllowCall(true)
                    .setShouldReject(false)
                    .setShouldAddToCallLog(true)
                    .setShouldShowNotification(true)
                    .build();

    private final CallFilterResultCallback mListener;
    private final Call mCall;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final TelecomSystem.SyncRoot mLock;
    private List<CallFilter> mFiltersList;
    private Executor mExecutor;
    private CallFilter mDummyComplete;
    private boolean mFinished;
    private CallFilteringResult mCurrentResult;
    private Context mContext;
    private Timeouts.Adapter mTimeoutsAdapter;

    private class PostFilterTask {
        private final CallFilter mFilter;

        public PostFilterTask(final CallFilter filter) {
            mFilter = filter;
        }

        public CallFilteringResult whenDone(CallFilteringResult result) {
            mFilter.result = result;
            for (CallFilter filter : mFilter.getFollowings()) {
                if (filter.decrementAndGetIndegree() == 0) {
                    scheduleFilter(filter);
                }
            }
            if (mFilter.equals(mDummyComplete)) {
                synchronized (mLock) {
                    mFinished = true;
                    mListener.onCallFilteringComplete(mCall, result);
                }
                mHandlerThread.quit();
            }
            return result;
        }
    }

    public IncomingCallFilterGraph(Call call, CallFilterResultCallback listener, Context context,
            Timeouts.Adapter timeoutsAdapter, TelecomSystem.SyncRoot lock) {
        mListener = listener;
        mCall = call;
        mFiltersList = new ArrayList<>();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mExecutor = mHandler::post;
        mFinished = false;
        mContext = context;
        mTimeoutsAdapter = timeoutsAdapter;
        mCurrentResult = DEFAULT_SCREENING_RESULT;
        mLock = lock;
    }

    public void addFilter(CallFilter filter) {
        mFiltersList.add(filter);
    }

    public void performFiltering() {

        CallFilter dummyStart = new CallFilter();
        mDummyComplete = new CallFilter();

        for (CallFilter filter : mFiltersList) {
            addEdge(dummyStart, filter);
        }
        for (CallFilter filter : mFiltersList) {
            addEdge(filter, mDummyComplete);
        }
        addEdge(dummyStart, mDummyComplete);

        scheduleFilter(dummyStart);
        mHandler.postDelayed(() -> {
            synchronized(mLock) {
                if (!mFinished) {
                    Log.i(this, "Graph timed out when perform filtering.");
                    mListener.onCallFilteringComplete(mCall, mCurrentResult);
                    mFinished = true;
                    mHandlerThread.quit();
                }
            }}, mTimeoutsAdapter.getCallScreeningTimeoutMillis(mContext.getContentResolver()));
    }

    private void scheduleFilter(CallFilter filter) {
        CallFilteringResult result = new CallFilteringResult.Builder()
                .setShouldAllowCall(true)
                .setShouldReject(false)
                .setShouldSilence(false)
                .setShouldAddToCallLog(true)
                .setShouldShowNotification(true)
                .build();
        for (CallFilter dependencyFilter : filter.getDependencies()) {
            result = result.combine(dependencyFilter.getResult());
        }
        mCurrentResult = result;
        final CallFilteringResult input = result;

        CompletableFuture<CallFilteringResult> startFuture =
                CompletableFuture.completedFuture(input);
        PostFilterTask postFilterTask = new PostFilterTask(filter);

        startFuture.thenComposeAsync(filter::startFilterLookup, mExecutor)
                .thenApplyAsync(postFilterTask::whenDone, mExecutor);
    }

    public static void addEdge(CallFilter before, CallFilter after) {
        before.addFollowings(after);
        after.addDependency(before);
    }
}
