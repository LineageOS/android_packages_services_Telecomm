/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.telecom.testapps;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.ConnectionRequest;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.Toast;

import com.android.server.telecom.testapps.R;

import java.util.Objects;

/**
 * Provides a sample third-party calling app UX which implements the self managed connection service
 * APIs.
 */
public class SelfManagedCallingActivity extends Activity {
    private static final String TAG = "SelfMgCallActivity";
    private SelfManagedCallList mCallList = SelfManagedCallList.getInstance();
    private CheckBox mCheckIfPermittedBeforeCalling;
    private Button mPlaceOutgoingCallButton;
    private Button mPlaceIncomingCallButton;
    private Button mPlaceIncomingCallDelayButton;
    private RadioButton mUseAcct1Button;
    private RadioButton mUseAcct2Button;
    private EditText mNumber;
    private ListView mListView;
    private SelfManagedCallListAdapter mListAdapter;

    private SelfManagedCallList.Listener mCallListListener = new SelfManagedCallList.Listener() {
        @Override
        public void onCreateIncomingConnectionFailed(ConnectionRequest request) {
            Log.i(TAG, "onCreateIncomingConnectionFailed " + request);
            Toast.makeText(SelfManagedCallingActivity.this,
                    R.string.incomingCallNotPermittedCS , Toast.LENGTH_SHORT).show();
        };

        @Override
        public void onCreateOutgoingConnectionFailed(ConnectionRequest request) {
            Log.i(TAG, "onCreateOutgoingConnectionFailed " + request);
            Toast.makeText(SelfManagedCallingActivity.this,
                    R.string.outgoingCallNotPermittedCS , Toast.LENGTH_SHORT).show();
        };

        @Override
        public void onConnectionListChanged() {
            Log.i(TAG, "onConnectionListChanged");
            mListAdapter.updateConnections();
        };
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int flags =
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;

        getWindow().addFlags(flags);
        setContentView(R.layout.self_managed_sample_main);
        mCheckIfPermittedBeforeCalling = (CheckBox) findViewById(
                R.id.checkIfPermittedBeforeCalling);
        mPlaceOutgoingCallButton = (Button) findViewById(R.id.placeOutgoingCallButton);
        mPlaceOutgoingCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placeOutgoingCall();
            }
        });
        mPlaceIncomingCallButton = (Button) findViewById(R.id.placeIncomingCallButton);
        mPlaceIncomingCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placeIncomingCall();
            }
        });

        mPlaceIncomingCallDelayButton = (Button) findViewById(R.id.placeIncomingCallDelayButton);
        mPlaceIncomingCallDelayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Delay the incoming call so that we can turn off the screen and
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                placeIncomingCall();
            }
        });
        mUseAcct1Button = (RadioButton) findViewById(R.id.useAcct1Button);
        mUseAcct2Button = (RadioButton) findViewById(R.id.useAcct2Button);
        mNumber = (EditText) findViewById(R.id.phoneNumber);
        mListView = (ListView) findViewById(R.id.callList);
        mCallList.setListener(mCallListListener);
        mCallList.registerPhoneAccounts(this);
        mListAdapter = new SelfManagedCallListAdapter(getLayoutInflater(),
                mCallList.getConnections());
        mListView.setAdapter(mListAdapter);
        Log.i(TAG, "onCreate - mCallList id " + Objects.hashCode(mCallList));
    }

    private PhoneAccountHandle getSelectedPhoneAccountHandle() {
        if (mUseAcct1Button.isChecked()) {
            return mCallList.getPhoneAccountHandle(SelfManagedCallList.SELF_MANAGED_ACCOUNT_1);
        } else if (mUseAcct2Button.isChecked()) {
            return mCallList.getPhoneAccountHandle(SelfManagedCallList.SELF_MANAGED_ACCOUNT_2);
        }
        return null;
    }

    private void placeOutgoingCall() {
        TelecomManager tm = TelecomManager.from(this);
        PhoneAccountHandle phoneAccountHandle = getSelectedPhoneAccountHandle();

        if (mCheckIfPermittedBeforeCalling.isChecked()) {
            if (!tm.isOutgoingCallPermitted(phoneAccountHandle)) {
                Toast.makeText(this, R.string.outgoingCallNotPermitted , Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                getSelectedPhoneAccountHandle());
        tm.placeCall(Uri.parse(mNumber.getText().toString()), extras);
    }

    private void placeIncomingCall() {
        TelecomManager tm = TelecomManager.from(this);
        PhoneAccountHandle phoneAccountHandle = getSelectedPhoneAccountHandle();

        if (mCheckIfPermittedBeforeCalling.isChecked()) {
            if (!tm.isIncomingCallPermitted(phoneAccountHandle)) {
                Toast.makeText(this, R.string.incomingCallNotPermitted , Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.parse(mNumber.getText().toString()));
        tm.addNewIncomingCall(getSelectedPhoneAccountHandle(), extras);
    }
}