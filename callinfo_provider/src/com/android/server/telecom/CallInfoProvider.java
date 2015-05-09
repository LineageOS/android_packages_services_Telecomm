/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.android.server.telecom;

import android.app.Notification;
import android.content.Context;

public final class CallInfoProvider extends CallsManagerListenerBase {
    public CallInfoProvider(Context context) {
    }

    public synchronized boolean shouldBlock(String number) {
        return false;
    }

    public boolean requiresNetwork() {
        return false;
    }

    public boolean providesCallInfo() {
        return false;
    }

    public boolean updateInfoForCall(MissedCallInfo call) {
        return false;
    }

    public void updateMissedCallNotification(Notification notification) {
    }
}
