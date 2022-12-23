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

package com.android.server.telecom.voip;

import android.os.OutcomeReceiver;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.Queue;

public class TransactionManager {
    private static final String TAG = "VoipCallTransactionManager";
    private static TransactionManager INSTANCE = null;
    private static final Object sLock = new Object();
    private Queue<VoipCallTransaction> mTransactions;
    private VoipCallTransaction mCurrentTransaction;

    public interface TransactionCompleteListener {
        void onTransactionCompleted(VoipCallTransactionResult result, String transactionName);
        void onTransactionTimeout(String transactionName);
    }

    private TransactionManager() {
        mTransactions = new ArrayDeque<>();
        mCurrentTransaction = null;

    }

    public static TransactionManager getInstance() {
        synchronized (sLock) {
            if (INSTANCE == null) {
                INSTANCE = new TransactionManager();
            }
        }
        return INSTANCE;
    }

    @VisibleForTesting
    public static TransactionManager getTestInstance() {
        return new TransactionManager();
    }

    public void addTransaction(VoipCallTransaction transaction,
            OutcomeReceiver<VoipCallTransactionResult, Exception> receiver) {
        synchronized (sLock) {
            mTransactions.add(transaction);
            transaction.setCompleteListener(new TransactionCompleteListener() {
                @Override
                public void onTransactionCompleted(VoipCallTransactionResult result,
                        String transactionName) {
                    if (result.getResult() == 0
                        /* TODO: change this to static value in TelecomManager */) {
                        receiver.onResult(result);
                    } else {
                        receiver.onError(new Exception());
                    }
                    finishTransaction();
                }

                @Override
                public void onTransactionTimeout(String transactionName) {
                    receiver.onResult(new VoipCallTransactionResult(
                            VoipCallTransactionResult.RESULT_FAILED, transactionName + " timeout"));
                    finishTransaction();
                }
            });
        }
        startTransactions();
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
            mCurrentTransaction.start();
        }
    }

    private void finishTransaction() {
        synchronized (sLock) {
            mCurrentTransaction = null;
        }
        startTransactions();
    }

    @VisibleForTesting
    public void clear() {
        synchronized (sLock) {
            for (VoipCallTransaction transaction : mTransactions) {
                transaction.finish();
            }
        }
    }
}
