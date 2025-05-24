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
package com.android.server.telecom;

import android.telecom.Logging.Runnable;

import java.util.concurrent.Executor;

/** An executor that starts a log session before executing a runnable */
public class LoggedExecutor implements Executor {
    private final Executor mDelegateExecutor;
    private final String mSessionName;
    private final Object mLock;

    /**
     * Creates a LoggedExecutor that wraps another executor to provide session-based logging
     * for submitted tasks using Telecom's custom Runnable.
     *
     * @param delegateExecutor The actual executor that will run the tasks.
     * @param sessionName The name for the logging subsession.
     * @param lock The synchronization lock. If null, the custom Runnable will create its own.
     */
    public LoggedExecutor(Executor delegateExecutor, String sessionName, Object lock) {
        this.mDelegateExecutor = delegateExecutor;
        this.mSessionName = sessionName;
        this.mLock = lock;
    }

    @Override
    public void execute(java.lang.Runnable command) {
        Runnable telecomSessionRunnable = new Runnable(mSessionName, mLock) {
            @Override
            public void loggedRun() {
                command.run();
            }
        };

        // telecomSessionRunnable.prepare:
        //      a. Calls Log.createSubsession() and stores the session.
        //      b. Returns the *inner* mRunnable (a standard java.lang.Runnable).
        java.lang.Runnable preparedInnerRunnable = telecomSessionRunnable.prepare();

        // Submit this 'preparedInnerRunnable' to the mDelegateExecutor.
        //    The run() method of 'preparedInnerRunnable' will handle continuing the session,
        //    calling the loggedRun() we defined above, and then ending the session.
        mDelegateExecutor.execute(preparedInnerRunnable);
    }
}