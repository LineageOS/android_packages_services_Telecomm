/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.os.UserHandle;

import com.android.internal.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class RoleManagerAdapterImpl implements RoleManagerAdapter {
    // TODO: replace with actual role manager const.
    private static final String ROLE_CALL_REDIRECTION_APP = "android.app.role.PROXY_CALLING_APP";
    // TODO: replace with actual role manager const.
    private static final String ROLE_CAR_MODE_DIALER = "android.app.role.ROLE_CAR_MODE_DIALER";
    // TODO: replace with actual role manager const.
    private static final String ROLE_CALL_SCREENING = "android.app.role.CALL_SCREENING_APP";
    // TODO: replace with actual role manager const.
    private static final String ROLE_CALL_COMPANION_APP =
            "android.app.role.ROLE_CALL_COMPANION_APP";

    private String mOverrideDefaultCallRedirectionApp = null;
    private String mOverrideDefaultCallScreeningApp = null;
    private String mOverrideDefaultCarModeApp = null;
    private List<String> mOverrideCallCompanionApps = new ArrayList<>();
    private UserHandle mCurrentUserHandle;

    public RoleManagerAdapterImpl() {
    }

    @Override
    public String getDefaultCallRedirectionApp() {
        if (mOverrideDefaultCallRedirectionApp != null) {
            return mOverrideDefaultCallRedirectionApp;
        }
        return getRoleManagerCallRedirectionApp();
    }

    @Override
    public void setTestDefaultCallRedirectionApp(String packageName) {
        mOverrideDefaultCallRedirectionApp = packageName;
    }

    @Override
    public String getDefaultCallScreeningApp() {
        if (mOverrideDefaultCallScreeningApp != null) {
            return mOverrideDefaultCallScreeningApp;
        }
        return getRoleManagerCallScreeningApp();
    }

    @Override
    public void setTestDefaultCallScreeningApp(String packageName) {
        mOverrideDefaultCallScreeningApp = packageName;
    }

    @Override
    public List<String> getCallCompanionApps() {
        List<String> callCompanionApps = new ArrayList<>();
        // List from RoleManager is not resizable. AbstractList.add action is not supported.
        callCompanionApps.addAll(getRoleManagerCallCompanionApps());
        callCompanionApps.addAll(mOverrideCallCompanionApps);
        return callCompanionApps;
    }

    @Override
    public void addOrRemoveTestCallCompanionApp(String packageName, boolean isAdded) {
        if (isAdded) {
            mOverrideCallCompanionApps.add(packageName);
        } else {
            mOverrideCallCompanionApps.remove(packageName);
        }
    }

    @Override
    public String getCarModeDialerApp() {
        if (mOverrideDefaultCarModeApp != null) {
            return mOverrideDefaultCarModeApp;
        }
        return getRoleManagerCarModeDialerApp();
    }

    @Override
    public void setTestAutoModeApp(String packageName) {
        mOverrideDefaultCarModeApp = packageName;
    }

    @Override
    public void setCurrentUserHandle(UserHandle currentUserHandle) {
        mCurrentUserHandle = currentUserHandle;
    }

    private String getRoleManagerCallScreeningApp() {
        // TODO: Link in RoleManager
        return null;
    }

    private String getRoleManagerCarModeDialerApp() {
        // TODO: Link in RoleManager
        return null;
    }

    private List<String> getRoleManagerCallCompanionApps() {
        // TODO: Link in RoleManager
        return Collections.emptyList();
    }

    private String getRoleManagerCallRedirectionApp() {
        // TODO: Link in RoleManager
        return null;
    }

    /**
     * Dumps the state of the {@link InCallController}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.print("DefaultCallRedirectionApp: ");
        if (mOverrideDefaultCallRedirectionApp != null) {
            pw.print("(override ");
            pw.print(mOverrideDefaultCallRedirectionApp);
            pw.print(") ");
            pw.print(getRoleManagerCallRedirectionApp());
        }
        pw.println();

        pw.print("DefaultCallScreeningApp: ");
        if (mOverrideDefaultCallScreeningApp != null) {
            pw.print("(override ");
            pw.print(mOverrideDefaultCallScreeningApp);
            pw.print(") ");
            pw.print(getRoleManagerCallScreeningApp());
        }
        pw.println();

        pw.print("DefaultCarModeDialerApp: ");
        if (mOverrideDefaultCallScreeningApp != null) {
            pw.print("(override ");
            pw.print(mOverrideDefaultCarModeApp);
            pw.print(") ");
            pw.print(getRoleManagerCarModeDialerApp());
        }
        pw.println();

        pw.print("DefaultCallCompanionApps: ");
        if (mOverrideDefaultCallScreeningApp != null) {
            pw.print("(override ");
            pw.print(mOverrideCallCompanionApps.stream().collect(Collectors.joining(", ")));
            pw.print(") ");
            List<String> appsInRole = getRoleManagerCallCompanionApps();
            if (appsInRole != null) {
                pw.print(appsInRole.stream().collect(Collectors.joining(", ")));
            }
        }
        pw.println();
    }
}
