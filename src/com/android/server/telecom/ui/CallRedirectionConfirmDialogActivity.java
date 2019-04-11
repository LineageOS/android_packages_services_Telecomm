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
 * limitations under the License
 */

package com.android.server.telecom.ui;

import com.android.server.telecom.R;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;
import com.android.server.telecom.components.TelecomBroadcastReceiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.telecom.Log;

/**
 * Dialog activity used when there is an ongoing call redirected by the call redirection service.
 * The dialog prompts the user to see if they want to place the redirected outgoing call.
 */
public class CallRedirectionConfirmDialogActivity extends Activity {
    public static final String EXTRA_REDIRECTION_OUTGOING_CALL_ID =
            "android.telecom.extra.REDIRECTION_OUTGOING_CALL_ID";
    public static final String EXTRA_REDIRECTION_APP_NAME =
            "android.telecom.extra.REDIRECTION_APP_NAME";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(this, "CallRedirectionConfirmDialogActivity onCreate.");
        final CharSequence redirectionAppName = getIntent().getStringExtra(
                EXTRA_REDIRECTION_APP_NAME);
        showDialog(redirectionAppName);
    }

    private void showDialog(final CharSequence redirectionAppName) {
        Log.i(this, "showDialog: confirming redirection with %s", redirectionAppName);
        CharSequence message = getString(
                R.string.alert_redirect_outgoing_call, redirectionAppName);
        final AlertDialog confirmDialog = new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(getString(R.string.alert_place_redirect_outgoing_call,
                        redirectionAppName), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent proceedWithRedirectedCall = new Intent(
                                TelecomBroadcastIntentProcessor
                                        .ACTION_PLACE_REDIRECTED_CALL, null,
                                CallRedirectionConfirmDialogActivity.this,
                                TelecomBroadcastReceiver.class);
                        proceedWithRedirectedCall.putExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID,
                                getIntent().getStringExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID));
                        sendBroadcast(proceedWithRedirectedCall);
                        dialog.dismiss();
                        finish();
                    }
                })
                .setNegativeButton(getString(R.string.alert_place_unredirect_outgoing_call,
                        redirectionAppName), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent proceedWithoutRedirectedCall = new Intent(
                                TelecomBroadcastIntentProcessor.ACTION_PLACE_UNREDIRECTED_CALL,
                                null, CallRedirectionConfirmDialogActivity.this,
                                TelecomBroadcastReceiver.class);
                        proceedWithoutRedirectedCall.putExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID,
                                getIntent().getStringExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID));
                        sendBroadcast(proceedWithoutRedirectedCall);
                        dialog.dismiss();
                        finish();
                    }
                })
                .setNeutralButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent cancelRedirectedCall = new Intent(
                                TelecomBroadcastIntentProcessor.ACTION_CANCEL_REDIRECTED_CALL,
                                null, CallRedirectionConfirmDialogActivity.this,
                                TelecomBroadcastReceiver.class);
                        cancelRedirectedCall.putExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID,
                                getIntent().getStringExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID));
                        sendBroadcast(cancelRedirectedCall);
                        dialog.dismiss();
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        Intent cancelRedirectedCall = new Intent(
                                TelecomBroadcastIntentProcessor.ACTION_CANCEL_REDIRECTED_CALL,
                                null, CallRedirectionConfirmDialogActivity.this,
                                TelecomBroadcastReceiver.class);
                        cancelRedirectedCall.putExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID,
                                getIntent().getStringExtra(EXTRA_REDIRECTION_OUTGOING_CALL_ID));
                        sendBroadcast(cancelRedirectedCall);
                        dialog.dismiss();
                        finish();
                    }
                })
                .create();
        confirmDialog.show();
        confirmDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setAllCaps(false);
        confirmDialog.getButton(DialogInterface.BUTTON_POSITIVE).setAllCaps(false);
        confirmDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setAllCaps(false);
    }
}
