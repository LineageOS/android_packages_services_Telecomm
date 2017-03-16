package com.android.server.telecom;

import android.content.Context;
import android.os.Environment;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

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
 * limitations under the License.
 */

public class SensitivePhoneNumbers {
    private final String LOG_TAG = this.getClass().getSimpleName();

    public static final String SENSIBLE_PHONENUMBERS_FILE_PATH = "etc/sensitive_pn.xml";
    private static final String ns = null;

    private HashMap<String, ArrayList<String>> mSensitiveNumbersMap = new HashMap<>();

    public SensitivePhoneNumbers() {
        loadSensiblePhoneNumbers();
    }

    private void loadSensiblePhoneNumbers() {
        FileReader sensiblePNReader;

        File sensiblePNFile = new File(Environment.getRootDirectory(),
                SENSIBLE_PHONENUMBERS_FILE_PATH);

        try {
            sensiblePNReader = new FileReader(sensiblePNFile);
        } catch (FileNotFoundException e) {
            Log.w(LOG_TAG, "Can not open " + sensiblePNFile.getAbsolutePath());
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(sensiblePNReader);
            parser.nextTag();

            readSensitivePNS(parser);

            sensiblePNReader.close();
        } catch (XmlPullParserException e) {
            Log.w(LOG_TAG, "Exception in spn-conf parser " + e);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Exception in spn-conf parser " + e);
        }
    }

    private void readSensitivePNS(XmlPullParser parser) throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, "sensitivePNS");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();
            if ("sensitivePN".equals(name)) {
                SensitivePhoneNumber sensitivePN = SensitivePhoneNumber.readSensitivePhoneNumbers(parser);
                mSensitiveNumbersMap.put(sensitivePN.getNetworkNumeric(), sensitivePN.getPhoneNumbers());
            } else {
                break;
            }
        }
    }

    public boolean isSensitiveNumber(Context context, String numberToCheck, String subId){
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        int subIdInt = SubscriptionManager.getDefaultSubscriptionId();
        try{
            subIdInt = Integer.valueOf(subId);
        }catch (NumberFormatException e) {
            Log.w(LOG_TAG, "Error parsing subId");
        }

        String networkUsed = telephonyManager.getNetworkOperator(subIdInt);
        if (!TextUtils.isEmpty(networkUsed)) {
            String networkMCC = networkUsed.substring(0, 3);
            return mSensitiveNumbersMap.containsKey(networkMCC) && mSensitiveNumbersMap.get(networkMCC).contains(numberToCheck);
        }
        return false;
    }
}
