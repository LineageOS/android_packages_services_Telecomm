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

package com.android.server.telecom;

import android.telecom.PhoneAccountHandle;

import com.android.internal.telecom.ICallEventCallback;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks all TransactionalServiceWrappers that have an ongoing call. Removes wrappers that have no
 * more calls.
 */
public class TransactionalServiceRepository {

    private static final Map<PhoneAccountHandle, TransactionalServiceWrapper> lookupTable =
            new HashMap<>();

    public TransactionalServiceRepository() {
    }

    public TransactionalServiceWrapper addNewCallForTransactionalServiceWrapper
            (PhoneAccountHandle phoneAccountHandle, ICallEventCallback callEventCallback,
                    CallsManager callsManager, Call call) {

        TransactionalServiceWrapper service = null;
        if (!hasExistingServiceWrapper(phoneAccountHandle)) {
            service = new TransactionalServiceWrapper(callEventCallback,
                    callsManager, phoneAccountHandle, call, this);
        } else {
            service = getTransactionalServiceWrapper(phoneAccountHandle);
            if (service == null) {
                throw new IllegalStateException("service is null");
            } else {
                service.trackCall(call);
            }
        }

        lookupTable.put(phoneAccountHandle, service);

        return service;
    }

    public TransactionalServiceWrapper getTransactionalServiceWrapper(PhoneAccountHandle pah) {
        return lookupTable.get(pah);
    }

    public boolean hasExistingServiceWrapper(PhoneAccountHandle pah) {
        return lookupTable.containsKey(pah);
    }

    public boolean removeServiceWrapper(PhoneAccountHandle pah) {
        if (!hasExistingServiceWrapper(pah)) {
            return false;
        }
        lookupTable.remove(pah);
        return true;
    }

}
