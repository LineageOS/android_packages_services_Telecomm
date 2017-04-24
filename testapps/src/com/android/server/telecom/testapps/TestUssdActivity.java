package com.android.server.telecom.testapps;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.Toast;

public class TestUssdActivity extends Activity {

    private EditText mUssdNumberView;
    private static Context context;
    public static final String LOG_TAG = "TestUssdActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TestUssdActivity.context = getApplicationContext();

        setContentView(R.layout.testussd_main);
        findViewById(R.id.place_ussd_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {

                placeUssdRequest();
            }
        });

        mUssdNumberView = (EditText) findViewById(R.id.number);
    }

    public static final class OnReceiveUssdResponseCallback extends
        TelephonyManager.OnReceiveUssdResponseCallback {

            OnReceiveUssdResponseCallback() {
            }

            public void onReceiveUssdResponse(String req, CharSequence message) {
                Log.i(LOG_TAG, "USSD Success:::" + req + "," + message);
                showToast("USSD Response Successly received for code:" + req + "," + message);
            }

            public void onReceiveUssdResponseFailed(String req, int resultCode) {
                Log.i(LOG_TAG, "USSD Fail:::" + req + "," + resultCode);
                showToast("USSD Response failed for code:" + req + "," + resultCode);
            }
    }

    private void placeUssdRequest() {

        String mUssdNumber = mUssdNumberView.getText().toString();
        if (mUssdNumber.equals("") || mUssdNumber == null) {
            mUssdNumber = "932";
        }
        mUssdNumber = "#" + mUssdNumber + "#";
        final TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        try {
            Handler h = new Handler(Looper.getMainLooper());
            OnReceiveUssdResponseCallback receiveUssdResponseCallback =
                    new OnReceiveUssdResponseCallback();

            telephonyManager.sendUssdRequest(mUssdNumber, receiveUssdResponseCallback, h);

        } catch (SecurityException e) {
            showToast("Permission check failed");
            return;
        }
    }

    private static void showToast(String message) {
        Toast.makeText(TestUssdActivity.context, message, Toast.LENGTH_SHORT).show();
    }
}