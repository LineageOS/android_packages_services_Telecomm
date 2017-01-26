/*
 * Copyright (C) 2015 Android Open Source Project
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

package com.android.server.telecom.testapps;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.telecom.Call;
import android.telecom.VideoProfile;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ListView;

public class TestInCallUI extends Activity {

    private ListView mListView;
    private TestCallList mCallList;

    /** ${inheritDoc} */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.incall_screen);

        mListView = (ListView) findViewById(R.id.callListView);
        mListView.setAdapter(new CallListAdapter(this));
        mListView.setVisibility(View.VISIBLE);

        mCallList = TestCallList.getInstance();
        mCallList.addListener(new TestCallList.Listener() {
            @Override
            public void onCallRemoved(Call call) {
                if (mCallList.size() == 0) {
                    Log.i(TestInCallUI.class.getSimpleName(), "Ending the incall UI");
                    finish();
                }
            }
        });

        View endCallButton = findViewById(R.id.end_call_button);
        View holdButton = findViewById(R.id.hold_button);
        View muteButton = findViewById(R.id.mute_button);
        View rttIfaceButton = findViewById(R.id.rtt_iface_button);
        View answerButton = findViewById(R.id.answer_button);

        endCallButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Call call = mCallList.getCall(0);
                if (call != null) {
                    call.disconnect();
                }
            }
        });
        holdButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Call call = mCallList.getCall(0);
                if (call != null) {
                    if (call.getState() == Call.STATE_HOLDING) {
                        call.unhold();
                    } else {
                        call.hold();
                    }
                }
            }
        });
        muteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Call call = mCallList.getCall(0);
                if (call != null) {

                }
            }
        });
        rttIfaceButton.setOnClickListener((view) -> {
            Call call = mCallList.getCall(0);
            if (call.isRttActive()) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClass(this, TestRttActivity.class);
                startActivity(intent);
            }
        });
        answerButton.setOnClickListener(view -> {
            Call call = mCallList.getCall(0);
            if (call.getState() == Call.STATE_RINGING) {
                call.answer(VideoProfile.STATE_AUDIO_ONLY);
            }
        });
    }

    /** ${inheritDoc} */
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }
}
