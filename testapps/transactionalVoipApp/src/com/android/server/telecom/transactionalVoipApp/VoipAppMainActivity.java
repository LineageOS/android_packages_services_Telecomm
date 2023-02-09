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
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallControl;
import android.telecom.CallException;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.View;
import android.widget.ToggleButton;

public class VoipAppMainActivity extends Activity {

    private static final String TAG = "VoipAppMainActivity";
    private static TelecomManager mTelecomManager;
    private MyVoipCall mCall1;
    private MyVoipCall mCall2;
    private ToggleButton mCallDirectionButton;

    PhoneAccountHandle handle = new PhoneAccountHandle(
            new ComponentName("com.android.server.telecom.transactionalVoipApp",
                    "com.android.server.telecom.transactionalVoipApp.VoipAppMainActivity"), "123");

    PhoneAccount mPhoneAccount = PhoneAccount.builder(handle, "test label")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED |
                    PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS).build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        mTelecomManager = getSystemService(TelecomManager.class);
        mCallDirectionButton = findViewById(R.id.callDirectionButton);

        // register account
        findViewById(R.id.registerButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTelecomManager.registerPhoneAccount(mPhoneAccount);
            }
        });

        // call 1 buttons
        findViewById(R.id.add_call_1_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bundle extras = new Bundle();
                extras.putString("testKey", "testValue");
                mCall1 = new MyVoipCall("1");
                addCall(mCall1, true);
            }
        });

        findViewById(R.id.disconnect_call_1_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnectCall(mCall1);
            }
        });


        //call 2 buttons
        findViewById(R.id.add_call_2_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bundle extras = new Bundle();
                extras.putString("call2extraKey", "call2Value");
                mCall2 = new MyVoipCall("2");
                addCall(mCall2, false);
            }
        });

        findViewById(R.id.set_call_2_active_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCallActive(mCall2);
            }
        });

        findViewById(R.id.disconnect_call_2_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnectCall(mCall2);
            }
        });
    }

    private void addCall(MyVoipCall call, boolean setActive) {
        int direction = (mCallDirectionButton.isChecked() ? DIRECTION_INCOMING
                : DIRECTION_OUTGOING);

        CallAttributes callAttributes = new CallAttributes.Builder(handle, direction, "Alan Turing",
                Uri.fromParts("tel", "abc", "123")).build();

        mTelecomManager.addCall(callAttributes, Runnable::run,
                new OutcomeReceiver<CallControl, CallException>() {
                    @Override
                    public void onResult(CallControl callControl) {
                        Log.i(TAG, "addCall: onResult: callback fired");
                        call.onAddCallControl(callControl);
                        if (setActive) {
                            setCallActive(call);
                        }
                    }

                    @Override
                    public void onError(CallException exception) {

                    }
                },
                call, call);
    }

    private void setCallActive(MyVoipCall call) {
        call.mCallControl.setActive(Runnable::run, new OutcomeReceiver<Void, CallException>() {
            @Override
            public void onResult(Void result) {
                Log.i(TAG, "setCallActive: onResult");
            }

            @Override
            public void onError(CallException exception) {
                Log.i(TAG, "setCallActive: onError");
            }
        });
    }

    private void disconnectCall(MyVoipCall call) {
        call.mCallControl.disconnect(new DisconnectCause(DisconnectCause.LOCAL), Runnable::run,
                new OutcomeReceiver<Void, CallException>() {
                    @Override
                    public void onResult(Void result) {
                        Log.i(TAG, "disconnectCall: onResult");
                    }

                    @Override
                    public void onError(CallException exception) {
                        Log.i(TAG, "disconnectCall: onError");
                    }
                });
    }
}
