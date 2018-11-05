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
 * limitations under the License.
 */

package com.android.server.telecom.components;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.server.telecom.R;

import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.text.TextUtils;

public class ChangeDefaultCallScreeningApp extends AlertActivity implements
        DialogInterface.OnClickListener {

    private static final String TAG = ChangeDefaultCallScreeningApp.class.getSimpleName();
    private ComponentName mNewComponentName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mNewComponentName = ComponentName.unflattenFromString(getIntent().getStringExtra(
            TelecomManager.EXTRA_DEFAULT_CALL_SCREENING_APP_COMPONENT_NAME));

        if (canChangeDefaultCallScreening()) {
            buildDialog();
        } else {
            finish();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case BUTTON_POSITIVE:
                try {
                    TelecomManager.from(this).setDefaultCallScreeningApp(mNewComponentName);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, e, e.getMessage());
                    break;
                }
                break;
            case BUTTON_NEGATIVE:
                break;
        }
    }

    private boolean canChangeDefaultCallScreening() {
        boolean canChange;
        String defaultComponentName = Settings.Secure
            .getStringForUser(getApplicationContext().getContentResolver(),
                Settings.Secure.CALL_SCREENING_DEFAULT_COMPONENT, UserHandle.USER_CURRENT);

        ComponentName oldComponentName = TextUtils.isEmpty(defaultComponentName) ? null
            : ComponentName.unflattenFromString(defaultComponentName);

        canChange = oldComponentName == null || !oldComponentName.getPackageName()
            .equals(mNewComponentName.getPackageName()) || !oldComponentName.flattenToString()
            .equals(mNewComponentName.flattenToString());

        return canChange;
    }

    private void buildDialog() {
        final String newPackageLabel = getApplicationLabel(mNewComponentName);
        final AlertController.AlertParams p = mAlertParams;

        p.mTitle = getString(R.string.change_default_call_screening_dialog_title, newPackageLabel);
        p.mMessage = getDialogBody(newPackageLabel);
        p.mPositiveButtonText = getString(
            R.string.change_default_call_screening_dialog_affirmative);
        p.mNegativeButtonText = getString(R.string.change_default_call_screening_dialog_negative);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonListener = this;

        setupAlert();
    }

    private String getDialogBody(String newPackageLabel) {
        final String oldPackage = Settings.Secure.getStringForUser(
            getApplicationContext().getContentResolver(),
            Settings.Secure.CALL_SCREENING_DEFAULT_COMPONENT, UserHandle.USER_CURRENT);
        ComponentName oldPackageComponentName = null;
        if (!TextUtils.isEmpty(oldPackage)) {
            oldPackageComponentName = ComponentName.unflattenFromString(oldPackage);
        }

        String dialogBody = getString(R.string.change_default_call_screening_warning_message,
            newPackageLabel);

        if (oldPackageComponentName != null) {
            dialogBody = getString(
                R.string.change_default_call_screening_warning_message_for_disable_old_app,
                getApplicationLabel(oldPackageComponentName)) + " "
                + dialogBody;
        }

        return dialogBody;
    }

    /**
     * Returns the application label that corresponds to the given package name
     *
     * @return Application label for the given package name, or null if not found.
     */
    private String getApplicationLabel(ComponentName componentName) {
        final PackageManager pm = getPackageManager();

        try {
            ApplicationInfo info = pm.getApplicationInfo(componentName.getPackageName(), 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Application info not found for packageName " + componentName
                .getPackageName());
        }

        return componentName.getPackageName();
    }
}
