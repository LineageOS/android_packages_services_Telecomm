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

package com.android.server.telecom.transactionalVoipApp;

import static android.telecom.CallAttributes.DIRECTION_INCOMING;
import static android.telecom.CallAttributes.DIRECTION_OUTGOING;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.telecom.CallAttributes;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public class VoipAppMainActivity extends Activity {
    private static final String TAG = "VoipAppMainActivity";
    private static final String ACT_STATE_TAG = "VoipActivityState";
    private static TelecomManager mTelecomManager;
    NotificationManager mNotificationManager;
    // Define callback intent filter
    private static final IntentFilter CALL_BACK_ACTION = new IntentFilter(
            TelecomManager.ACTION_CALL_BACK);
    // Map call UUIDs to the associated call attributes needed for the callback
    private static final Map<String, CallAttributes> mUuidToAttributes = new HashMap<>();
    // Callback receiver to handle callback intents from Telecom
    private final BroadcastReceiver mCallbackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.telecom.Log.startSession("VAMA.mCR");
            try {
                if (TelecomManager.ACTION_CALL_BACK.equals(intent.getAction())) {
                    String uuid = intent.getStringExtra(TelecomManager.EXTRA_UUID);
                    Log.i(TAG, "Received action callback intent. Attempting to place a call "
                            + "with uuid - " + uuid);
                    // Direction should always be outgoing for callback induced calls.
                    startInCallActivity(DIRECTION_OUTGOING, uuid);
                }
            } finally {
                android.telecom.Log.endSession();
            }
        }
    };

    // Save the call information from the intent passed from InCallActivity
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            // Get the attributes and uuid from InCallActivity once the call is disconnected and
            // save the mapping for supporting callback
            String uuid = data.getStringExtra(Utils.sCall_UUID_EXTRA_KEY);
            CallAttributes callAttributes = data.getParcelableExtra(Utils.sCall_ATTRIBUTE_KEY,
                    CallAttributes.class);
            Log.i(TAG, "Received uuid " + uuid + " and CallAttributes - " + callAttributes
                    + " from InCallActivity");
            if (uuid != null && callAttributes != null) {
                mUuidToAttributes.put(uuid, callAttributes);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, ACT_STATE_TAG + "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        mTelecomManager = getSystemService(TelecomManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);
        // create a notification channel
        if (mNotificationManager != null) {
            mNotificationManager.createNotificationChannel(new NotificationChannel(
                    Utils.CHANNEL_ID, "new call channel",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }

        // register account
        findViewById(R.id.registerButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTelecomManager.registerPhoneAccount(Utils.PHONE_ACCOUNT);
            }
        });

        // Start a foreground service that will post a notification within 10 seconds.
        // This is helpful for debugging scenarios where the app is in the background and posting
        // an incoming call notification.
        findViewById(R.id.startForegroundService).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent startForegroundService = new Intent(getApplicationContext(),
                        BackgroundIncomingCallService.class);
                getApplicationContext().startForegroundService(startForegroundService);
            }
        });


        // post a new call notification and start an InCall activity
        findViewById(R.id.startOutgoingCall).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInCallActivity(DIRECTION_OUTGOING, null);
            }
        });

        // post a new call notification and start an InCall activity
        findViewById(R.id.startIncomingCall).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInCallActivity(DIRECTION_INCOMING, null);
            }
        });

        findViewById(R.id.registerCallbackIntent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getApplicationContext().registerReceiver(mCallbackReceiver, CALL_BACK_ACTION);
            }
        });

        findViewById(R.id.openDialer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDialerActivity();
            }
        });

        // Handle the callback intent if present
        maybeHandleCallbackIntent(getIntent());
    }

    private void startInCallActivity(int direction, String uuid) {
        Bundle extras = new Bundle();
        extras.putInt(Utils.sCALL_DIRECTION_KEY, direction);
        // Store the call attributes in the intent itself. From an app level, we will map individual
        // UUIDS to the attributes and verify that the initiated callbacks are valid or not before
        // we start the in-call activity.
        CallAttributes callAttributes;
        // If the UUID isn't null, then we're initiating a callback. Search for the associated
        // call attributes for the callback. Otherwise, show an error dialogue.
        if (uuid != null) {
            if (!mUuidToAttributes.containsKey(uuid)) {
                Toast.makeText(this, getString(R.string.uuid_not_found),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            extras.putString(Utils.sCall_UUID_EXTRA_KEY, uuid);
            callAttributes = mUuidToAttributes.get(uuid);
        } else {
            callAttributes = Utils.getCallAttributes(direction);
        }
        extras.putParcelable(Utils.sCall_ATTRIBUTE_KEY, callAttributes);
        Intent intent = new Intent(getApplicationContext(), InCallActivity.class);
        intent.putExtra(Utils.sEXTRAS_KEY, extras);
        // Calling startActivityForResult allows InCallActivity to send data back to main activity
        // containing the call attributes and uuid for the call.
        startActivityForResult(intent, 1 /* requestCode */);
    }

    private void startDialerActivity() {
        Intent intent = new Intent(getApplicationContext(), TestDialerActivity.class);
        startActivity(intent);
    }

    /**
     * Checks the intent for the callback action and tries to make a call with the provided uuid
     * if present.
     */
    private void maybeHandleCallbackIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        final String action = intent.getAction();
        if (TelecomManager.ACTION_CALL_BACK.equals(action)) {
            String uuid = intent.getStringExtra(TelecomManager.EXTRA_UUID);
            if (uuid != null && !uuid.isEmpty()) {
                startInCallActivity(DIRECTION_OUTGOING, uuid);
            }
        }
    }

    @Override
    protected void onResume() {
        Log.i(TAG, ACT_STATE_TAG + " onResume: When the activity enters the Resumed state,"
                + " it comes to the foreground");
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.i(TAG, ACT_STATE_TAG + " onPause: The system calls this method as the first"
                + " indication that the user is leaving your activity.  It indicates that the"
                + " activity is no longer in the foreground, but it is still visible if the user"
                + " is in multi-window mode");
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.i(TAG, ACT_STATE_TAG + "onStop: When your activity is no longer visible to"
                + " the user, it enters the Stopped state,");
        super.onStop();
    }

    @Override
    protected void onRestart() {
        Log.i(TAG, ACT_STATE_TAG + " onRestart: onStop has called onRestart and the "
                + "activity comes back to interact with the user");
        super.onRestart();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, ACT_STATE_TAG + " onDestroy: is called before the activity is"
                + " destroyed. ");
        Utils.clearNotification(getApplicationContext());
        super.onDestroy();
    }
}
