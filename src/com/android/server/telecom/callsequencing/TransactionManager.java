/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.telecom.callsequencing;

import static android.telecom.CallException.CODE_OPERATION_TIMED_OUT;

import android.os.OutcomeReceiver;
import android.telecom.TelecomManager;
import android.telecom.CallException;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.AnomalyReporterAdapter;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.flags.Flags;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TransactionManager {
    private static final String TAG = "CallTransactionManager";
    private static final int TRANSACTION_HISTORY_SIZE = 20;
    private static TransactionManager INSTANCE = null;
    private static final Object sLock = new Object();
    private final Queue<CallTransaction> mTransactions;
    private final Deque<CallTransaction> mCompletedTransactions;
    private CallTransaction mCurrentTransaction;
    private boolean mProcessingCallSequencing;
    private AnomalyReporterAdapter mAnomalyReporter;
    private FeatureFlags mFeatureFlags;
    public static final UUID TRANSACTION_MANAGER_TIMEOUT_UUID =
            UUID.fromString("9ccce52e-6694-4357-9e5e-516a9531b062");
    public static final String TRANSACTION_MANAGER_TIMEOUT_MSG =
            "TransactionManager hit a timeout while processing a transaction";

    public interface TransactionCompleteListener {
        void onTransactionCompleted(CallTransactionResult result, String transactionName);
        void onTransactionTimeout(String transactionName);
    }

    private TransactionManager() {
        mTransactions = new ArrayDeque<>();
        mCurrentTransaction = null;
        mCompletedTransactions = new ArrayDeque<>();
    }

    public static TransactionManager getInstance() {
        synchronized (sLock) {
            if (INSTANCE == null) {
                INSTANCE = new TransactionManager();
            }
        }
        return INSTANCE;
    }

    public void setFeatureFlag(FeatureFlags flag){
       mFeatureFlags = flag;
    }

    public void setAnomalyReporter(AnomalyReporterAdapter callAnomalyReporter){
        mAnomalyReporter = callAnomalyReporter;
    }

    @VisibleForTesting
    public static TransactionManager getTestInstance() {
        return new TransactionManager();
    }

    public CompletableFuture<Boolean> addTransaction(CallTransaction transaction,
            OutcomeReceiver<CallTransactionResult, CallException> receiver) {
        CompletableFuture<Boolean> transactionCompleteFuture = new CompletableFuture<>();
        synchronized (sLock) {
            mTransactions.add(transaction);
        }
        transaction.setCompleteListener(new TransactionCompleteListener() {
            @Override
            public void onTransactionCompleted(CallTransactionResult result,
                    String transactionName) {
                Log.i(TAG, String.format("transaction %s completed: with result=[%d]",
                        transactionName, result.getResult()));
                try {
                    if (result.getResult() == TelecomManager.TELECOM_TRANSACTION_SUCCESS) {
                        receiver.onResult(result);
                        transactionCompleteFuture.complete(true);
                    } else {
                        receiver.onError(
                                new CallException(result.getMessage(),
                                        result.getResult()));
                        transactionCompleteFuture.complete(false);
                    }
                } catch (Exception e) {
                    Log.e(TAG, String.format("onTransactionCompleted: Notifying transaction result"
                            + " %s resulted in an Exception.", result), e);
                    transactionCompleteFuture.complete(false);
                }
                finishTransaction();
            }

            @Override
            public void onTransactionTimeout(String transactionName){
                Log.i(TAG, String.format("transaction %s timeout", transactionName));
                try {
                    receiver.onError(new CallException(transactionName + " timeout",
                            CODE_OPERATION_TIMED_OUT));
                    transactionCompleteFuture.complete(false);
                    if (mFeatureFlags != null && mAnomalyReporter != null &&
                            mFeatureFlags.enableCallExceptionAnomReports()) {
                        mAnomalyReporter.reportAnomaly(
                                TRANSACTION_MANAGER_TIMEOUT_UUID,
                                TRANSACTION_MANAGER_TIMEOUT_MSG);
                    }
                } catch (Exception e) {
                    Log.e(TAG, String.format("onTransactionTimeout: Notifying transaction "
                            + " %s resulted in an Exception.", transactionName), e);
                    transactionCompleteFuture.complete(false);
                }
                finishTransaction();
            }
        });

        startTransactions();
        return transactionCompleteFuture;
    }

    private void startTransactions() {
        synchronized (sLock) {
            if (mTransactions.isEmpty()) {
                // No transaction waiting for process
                return;
            }

            if (mCurrentTransaction != null) {
                // Ongoing transaction
                return;
            }
            mCurrentTransaction = mTransactions.poll();
        }
        mCurrentTransaction.start();
    }

    private void finishTransaction() {
        synchronized (sLock) {
            if (mCurrentTransaction != null) {
                addTransactionToHistory(mCurrentTransaction);
                mCurrentTransaction = null;
            }
        }
        startTransactions();
    }

    @VisibleForTesting
    public void clear() {
        List<CallTransaction> pendingTransactions;
        synchronized (sLock) {
            pendingTransactions = new ArrayList<>(mTransactions);
        }
        for (CallTransaction t : pendingTransactions) {
            t.finish(new CallTransactionResult(CallException.CODE_ERROR_UNKNOWN
                    /* TODO:: define error b/335703584 */, "clear called"));
        }
    }

    private void addTransactionToHistory(CallTransaction t) {
        mCompletedTransactions.add(t);
        if (mCompletedTransactions.size() > TRANSACTION_HISTORY_SIZE) {
            mCompletedTransactions.poll();
        }
    }

    /**
     * Called when the dumpsys is created for telecom to capture the current state.
     */
    public void dump(IndentingPrintWriter pw) {
        synchronized (sLock) {
            pw.println("Pending Transactions:");
            pw.increaseIndent();
            for (CallTransaction t : mTransactions) {
                printPendingTransactionStats(t, pw);
            }
            pw.decreaseIndent();

            pw.println("Ongoing Transaction:");
            pw.increaseIndent();
            if (mCurrentTransaction != null) {
                printPendingTransactionStats(mCurrentTransaction, pw);
            }
            pw.decreaseIndent();

            pw.println("Completed Transactions:");
            pw.increaseIndent();
            for (CallTransaction t : mCompletedTransactions) {
                printCompleteTransactionStats(t, pw);
            }
            pw.decreaseIndent();
        }
    }

    /**
     * Recursively print the pending {@link CallTransaction} stats for logging purposes.
     * @param t The transaction that stats should be printed for
     * @param pw The IndentingPrintWriter to print the result to
     */
    private void printPendingTransactionStats(CallTransaction t, IndentingPrintWriter pw) {
        CallTransaction.Stats s = t.getStats();
        if (s == null) {
            pw.println(String.format(Locale.getDefault(), "%s: <NO STATS>", t.mTransactionName));
            return;
        }
        pw.println(String.format(Locale.getDefault(),
                "[%s] %s: (result=[%s]), (created -> now : [%+d] mS),"
                        + " (created -> started : [%+d] mS),"
                        + " (started -> now : [%+d] mS)",
                s.addedTimeStamp, t.mTransactionName, parseTransactionResult(s),
                s.measureTimeSinceCreatedMs(), s.measureCreatedToStartedMs(),
                s.measureTimeSinceStartedMs()));

        if (t.mSubTransactions == null || t.mSubTransactions.isEmpty()) {
            return;
        }
        pw.increaseIndent();
        for (CallTransaction subTransaction : t.mSubTransactions) {
            printPendingTransactionStats(subTransaction, pw);
        }
        pw.decreaseIndent();
    }

    /**
     * Recursively print the complete Transaction stats for logging purposes.
     * @param t The transaction that stats should be printed for
     * @param pw The IndentingPrintWriter to print the result to
     */
    private void printCompleteTransactionStats(CallTransaction t, IndentingPrintWriter pw) {
        CallTransaction.Stats s = t.getStats();
        if (s == null) {
            pw.println(String.format(Locale.getDefault(), "%s: <NO STATS>", t.mTransactionName));
            return;
        }
        pw.println(String.format(Locale.getDefault(),
                "[%s] %s: (result=[%s]), (created -> started : [%+d] mS), "
                        + "(started -> completed : [%+d] mS)",
                s.addedTimeStamp, t.mTransactionName, parseTransactionResult(s),
                s.measureCreatedToStartedMs(), s.measureStartedToCompletedMs()));

        if (t.mSubTransactions == null || t.mSubTransactions.isEmpty()) {
            return;
        }
        pw.increaseIndent();
        for (CallTransaction subTransaction : t.mSubTransactions) {
            printCompleteTransactionStats(subTransaction, pw);
        }
        pw.decreaseIndent();
    }

    private String parseTransactionResult(CallTransaction.Stats s) {
        if (s.isTimedOut()) return "TIMED OUT";
        if (s.getTransactionResult() == null) return "PENDING";
        if (s.getTransactionResult().getResult() == CallTransactionResult.RESULT_SUCCEED) {
            return "SUCCESS";
        }
        return s.getTransactionResult().toString();
    }
}
