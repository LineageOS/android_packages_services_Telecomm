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

package com.android.server.telecom.tests;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.os.OutcomeReceiver;
import android.telecom.CallException;
import android.telecom.DisconnectCause;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.CallTransactionResult;
import com.android.server.telecom.callsequencing.TransactionManager;
import com.android.server.telecom.callsequencing.TransactionalCallSequencingAdapter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


/**
 * Unit tests for {@link TransactionalCallSequencingAdapter}.
 *
 * These tests verify the behavior of the TransactionalCallSequencingAdapter, focusing on
 * how it interacts with the TransactionManager and CallsManager, particularly in the
 * context of asynchronous operations and feature flag configurations (e.g., setting
 * rejected calls to a disconnected state).
 */
public class TransactionalCallSequencingAdapterTest extends TelecomTestCase {

    private static final String CALL_ID_1 = "1";
    private static final DisconnectCause REJECTED_DISCONNECT_CAUSE =
            new DisconnectCause(DisconnectCause.REJECTED);

    @Mock private Call mMockCall1;
    @Mock private Context mMockContext;
    @Mock private CallsManager mCallsManager;
    @Mock private TransactionManager mTransactionManager;

    private TransactionalCallSequencingAdapter mAdapter;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        when(mMockCall1.getId()).thenReturn(CALL_ID_1);
        when(mMockContext.getResources()).thenReturn(Mockito.mock(Resources.class));
        mAdapter = new TransactionalCallSequencingAdapter(
                mTransactionManager, mCallsManager, true);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Tests the scenario where an incoming call is rejected and the onSetDisconnect is called.
     * Verifies that {@link CallsManager#markCallAsDisconnected} *is* called and that the
     * {@link OutcomeReceiver} receives the correct result, handling the asynchronous nature of
     * the operation.
     */
    @Test
    public void testOnSetDisconnected() {
        // GIVEN -a new incoming call that is rejected

        // Create a CompletableFuture to control the asynchronous operation.
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Mock the TransactionManager's addTransaction method.
        setupAddTransactionMock(future);

        // Create a mock OutcomeReceiver to verify interactions.
        OutcomeReceiver<CallTransactionResult, CallException> resultReceiver =
                mock(OutcomeReceiver.class);

        // WHEN - Call onSetDisconnected and get the result future.
        mAdapter.onSetDisconnected(
                mMockCall1,
                REJECTED_DISCONNECT_CAUSE,
                mock(CallTransaction.class),
                resultReceiver);

        // Simulate the asynchronous operation completing.
        completeAddTransactionSuccessfully(future);

        // THEN - Verify that markCallAsDisconnected and the receiver's onResult were called.
        verifyMarkCallAsDisconnectedAndReceiverResult(resultReceiver);
    }
    /**
     * Sets up the mock behavior for {@link TransactionManager#addTransaction}.
     *
     * @param future The CompletableFuture to be returned by the mocked method.
     */
    private void setupAddTransactionMock(CompletableFuture<Boolean> future) {
        when(mTransactionManager.addTransaction(any(), any())).thenAnswer(invocation -> {
            return future; // Return the provided future.
        });
    }
    /**
     * Simulates the successful completion of the asynchronous operation tracked by the given
     * future. Captures the {@link OutcomeReceiver} passed to
     * {@link TransactionManager#addTransaction}, completes the future, and invokes
     * {@link OutcomeReceiver#onResult} with a successful result.
     *
     * @param future The CompletableFuture to complete.
     */
    private void completeAddTransactionSuccessfully(CompletableFuture<Boolean> future) {
        // Capture the OutcomeReceiver passed to addTransaction.
        ArgumentCaptor<OutcomeReceiver<CallTransactionResult, CallException>> captor =
                ArgumentCaptor.forClass(OutcomeReceiver.class);
        verify(mTransactionManager).addTransaction(any(CallTransaction.class), captor.capture());

        // Complete the future to signal the end of the asynchronous operation.
        future.complete(true);

        // Create a successful CallTransactionResult.
        CallTransactionResult callTransactionResult = new CallTransactionResult(
                CallTransactionResult.RESULT_SUCCEED,
                "EndCallTransaction: RESULT_SUCCEED");

        // Invoke onResult on the captured OutcomeReceiver.
        captor.getValue().onResult(callTransactionResult);

    }
    /**
     * Verifies that {@link CallsManager#markCallAsDisconnected} and the provided
     * {@link OutcomeReceiver}'s {@code onResult} method were called.  Also waits for the future
     * to complete.
     *
     * @param resultReceiver The mock OutcomeReceiver.
     */
    private void verifyMarkCallAsDisconnectedAndReceiverResult(
            OutcomeReceiver<CallTransactionResult, CallException> resultReceiver) {
        verify(mCallsManager, times(1)).markCallAsDisconnected(
                mMockCall1,
                REJECTED_DISCONNECT_CAUSE);
        verify(resultReceiver).onResult(any());
    }
}