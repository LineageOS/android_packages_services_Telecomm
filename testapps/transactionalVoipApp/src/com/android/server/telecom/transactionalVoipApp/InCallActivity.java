/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.telecom.CallAttributes.AUDIO_CALL;
import static android.telecom.CallAttributes.DIRECTION_INCOMING;
import static android.telecom.CallAttributes.DIRECTION_OUTGOING;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallControl;
import android.telecom.CallEndpoint;
import android.telecom.CallException;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class InCallActivity extends Activity {
    private static final String TAG = "InCallActivity";
    private final AudioManager.AudioRecordingCallback mAudioRecordingCallback =
            Utils.getAudioRecordingCallback();
    private static TelecomManager mTelecomManager;
    private MyVoipCall mVoipCall;
    private MediaPlayer mMediaPlayer;
    private AudioRecord mAudioRecord;
    private int mCallDirection = DIRECTION_INCOMING;
    private CallAttributes mCallAttributes;
    private String mCallId;
    private TextView mCurrentEndpointTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "#onCreate: in function");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.in_call_activity);

        Bundle extras = getIntent().getExtras();
        // Copy the extras with properties like call direction into the extras so the below
        // code can access them.
        if (extras != null && extras.containsKey(Utils.sEXTRAS_KEY)) {
            extras.putAll(extras.getBundle(Utils.sEXTRAS_KEY));
        }
        if (extras != null) {
            mCallDirection = extras.getInt(Utils.sCALL_DIRECTION_KEY, DIRECTION_INCOMING);
            mCallAttributes = extras.getParcelable(Utils.sCall_ATTRIBUTE_KEY, CallAttributes.class);
        }
        if (mCallAttributes == null) {
            Toast.makeText(this, getString(R.string.call_attributes_empty_error),
                    Toast.LENGTH_SHORT).show();
            mCallAttributes = Utils.getCallAttributes(mCallDirection);
        }
        updatePhoneNumber();
        mCurrentEndpointTextView = findViewById(R.id.current_endpoint);
        mCurrentEndpointTextView.setText("Endpoint/Audio Route NOT ESTABLISHED");
        updateCallId();
        mTelecomManager = getSystemService(TelecomManager.class);
        mMediaPlayer = Utils.createMediaPlayer(getApplicationContext());
        mAudioRecord = Utils.createAudioRecord();
        mAudioRecord.registerAudioRecordingCallback(Runnable::run, mAudioRecordingCallback);

        if (mVoipCall == null) {
            addCall();
        }

        findViewById(R.id.set_call_active_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateCurrentEndpoint();
                if (canUseCallControl()) {
                    mVoipCall.mCallControl.setActive(Runnable::run,
                            Utils.getLoggableOutcomeReceiver("setActive"));
                }
                mAudioRecord.startRecording();
                mMediaPlayer.start();
            }
        });


        findViewById(R.id.answer_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateCurrentEndpoint();
                if (canUseCallControl() && mCallDirection != DIRECTION_OUTGOING) {
                    mVoipCall.mCallControl.answer(AUDIO_CALL, Runnable::run,
                            Utils.getLoggableOutcomeReceiver("answer"));
                    mAudioRecord.startRecording();
                    mMediaPlayer.start();
                }
            }
        });


        findViewById(R.id.set_call_inactive_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (canUseCallControl()) {
                    mVoipCall.mCallControl.setInactive(Runnable::run,
                            Utils.getLoggableOutcomeReceiver("setInactive"));
                }
                mAudioRecord.stop();
                mMediaPlayer.pause();
            }
        });

        findViewById(R.id.disconnect_call_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnectAndStopAudio();
                // Send attributes and uuid back to main activity for referencing
                Intent intent = new Intent();
                intent.putExtra(Utils.sCall_UUID_EXTRA_KEY, mCallId);
                intent.putExtra(Utils.sCall_ATTRIBUTE_KEY, mCallAttributes);
                setResult(Activity.RESULT_OK, intent);
                finish();
            }
        });

        findViewById(R.id.start_stream_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (canUseCallControl()) {
                    mVoipCall.mCallControl.startCallStreaming(Runnable::run,
                            Utils.getLoggableOutcomeReceiver("startCallStream"));
                }
            }
        });

        findViewById(R.id.request_earpiece).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (canUseCallControl() && mVoipCall.mEarpieceEndpoint != null) {
                    requestEndpointChange(mVoipCall.mEarpieceEndpoint,
                            "Request EARPIECE Endpoint:");
                }
            }
        });

        findViewById(R.id.request_speaker).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (canUseCallControl() && mVoipCall.mSpeakerEndpoint != null) {
                    requestEndpointChange(mVoipCall.mSpeakerEndpoint,
                            "Request SPEAKER Endpoint:");
                }
            }
        });

        findViewById(R.id.request_bluetooth).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (canUseCallControl() && mVoipCall.mBluetoothEndpoint != null) {
                    requestEndpointChange(mVoipCall.mBluetoothEndpoint,
                            "Request BLUETOOTH Endpoint:");
                }
            }
        });

        findViewById(R.id.crash_app).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // To test edge cases, it may be useful to crash the app. To do this, throwing a
                // RuntimeException is sufficient.
                throw new RuntimeException(
                        "Intentionally throwing RuntimeException from InCallActivity");
            }
        });

        findViewById(R.id.updateCallStyleNotification).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Utils.updateCallStyleNotification_toOngoingCall(getApplicationContext());
                    }
                });
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "onStop: InCallActivity has stopped");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy: InCallActivity has been destroyed");
        disconnectAndStopAudio();
        super.onDestroy();
    }

    private boolean canUseCallControl() {
        return mVoipCall != null && mVoipCall.mCallControl != null;
    }

    private void updateCurrentEndpoint() {
        if (mCurrentEndpointTextView != null) {
            if (mVoipCall != null && mVoipCall.mCurrentEndpoint != null) {
                mCurrentEndpointTextView.setText("CallEndpoint=[" +
                        mVoipCall.mCurrentEndpoint.getEndpointName() + "]");
            }
        }
    }

    private void updateCurrentEndpointWithOnResult(CallEndpoint endpoint) {
        if (mCurrentEndpointTextView != null) {
            if (mVoipCall != null && mVoipCall.mCurrentEndpoint != null) {
                mCurrentEndpointTextView.setText("CallEndpoint=[" +
                        endpoint.getEndpointName() + "]");
            }
        }
    }

    private void updateCallId() {
        TextView view = findViewById(R.id.getCallIdTextView);
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        if (canUseCallControl()) {
            mCallId = mVoipCall.mCallControl.getCallId().toString();
            sb.append(mCallId);
        } else {
            sb.append("Error Getting Id");
        }
        sb.append("]");
        try {
            view.setText(sb.toString());
        }
        catch (Exception e){
            // ignore updating the ui
        }
    }

    private void updatePhoneNumber() {
        TextView view = findViewById(R.id.phoneNumber);
        String phoneNumber = mCallAttributes.getAddress().getSchemeSpecificPart();
        view.setText(phoneNumber);
    }

    private void addCall() {
        mVoipCall = new MyVoipCall("123");

        mTelecomManager.addCall(mCallAttributes, Runnable::run,
                new OutcomeReceiver<CallControl, CallException>() {
                    @Override
                    public void onResult(CallControl callControl) {
                        Log.i(TAG, "addCall: onResult: callback fired");
                        Utils.postIncomingCallStyleNotification(getApplicationContext());
                        mVoipCall.onAddCallControl(callControl);
                        updateCallId();
                        updateCurrentEndpoint();
                    }

                    @Override
                    public void onError(CallException exception) {

                    }
                },
                mVoipCall, mVoipCall);
    }

    private void disconnectAndStopAudio() {
        if (mVoipCall != null) {
            mVoipCall.mCallControl.disconnect(
                    new DisconnectCause(DisconnectCause.LOCAL),
                    Runnable::run,
                    Utils.getLoggableOutcomeReceiver("disconnect"));
        }
        mMediaPlayer.stop();
        mAudioRecord.stop();
        try {
            mAudioRecord.unregisterAudioRecordingCallback(mAudioRecordingCallback);
            Utils.clearNotification(getApplicationContext());
        } catch (Exception e) {
            // pass through
        }
    }

    private void requestEndpointChange(CallEndpoint endpoint, String tag) {
        mVoipCall.mCallControl.requestCallEndpointChange(
                endpoint,
                Runnable::run,
                new OutcomeReceiver<Void, CallException>() {
                    @Override
                    public void onResult(Void result) {
                        Log.i(TAG, String.format("requestEndpointChange: success w/ %s", tag));
                        updateCurrentEndpointWithOnResult(endpoint);
                    }

                    @Override
                    public void onError(CallException e) {
                        Log.i(TAG, String.format("requestEndpointChange: %s failed to switch to "
                                + "endpoint=[%s] due to exception=[%s]", tag, endpoint, e));
                    }
                });
    }
}
