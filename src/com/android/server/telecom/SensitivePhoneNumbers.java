/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2017 The LineageOS Project
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

package com.android.server.telecom;

import android.content.Context;
import android.os.Environment;
import android.telephony.PhoneNumberUtils;
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

public class SensitivePhoneNumbers {
    private final String LOG_TAG = this.getClass().getSimpleName();

    public static final String SENSITIVE_PHONENUMBERS_FILE_PATH = "etc/sensitive_pn.xml";
    private static final String ns = null;

    private HashMap<String, ArrayList<String>> mSensitiveNumbersMap = new HashMap<>();

    public SensitivePhoneNumbers() {
        loadSensitivePhoneNumbers();
    }

    private void loadSensitivePhoneNumbers() {
        FileReader sensitiveNumberReader;

        File sensitiveNumberFile = new File(Environment.getRootDirectory(),
                SENSITIVE_PHONENUMBERS_FILE_PATH);

        try {
            sensitiveNumberReader = new FileReader(sensitiveNumberFile);
        } catch (FileNotFoundException e) {
            Log.w(LOG_TAG, "Can not open " + sensitiveNumberFile.getAbsolutePath());
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(sensitiveNumberReader);
            parser.nextTag();
            readSensitiveNumbers(parser);
            sensitiveNumberReader.close();
        } catch (XmlPullParserException e) {
            Log.w(LOG_TAG, "XML Parsing exception in reading sensitive_pn.xml: " + e);
        } catch (IOException e) {
            Log.w(LOG_TAG, "IO Exception in reading in reading sesnsitive_pn.xml" + e);
        }
    }

    private void readSensitiveNumbers(XmlPullParser parser)
                throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, "sensitivePNS");

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            if ("sensitivePN".equals(name)) {
                SensitivePhoneNumber sensitiveNumbers = SensitivePhoneNumber
                        .readSensitivePhoneNumbers(parser);
                String[] mccs = sensitiveNumbers.getNetworkNumeric().split(",");
                ArrayList<String> sensitiveNums = sensitiveNumbers.getPhoneNumbers();
                for (String mcc : mccs) {
                    mSensitiveNumbersMap.put(mcc, sensitiveNums);
                }
            } else {
                break;
            }
        }
    }

    public boolean isSensitiveNumber(Context context, String numberToCheck) {
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        String networkUsed = telephonyManager.getNetworkOperator();
        if (!TextUtils.isEmpty(networkUsed)) {
            String networkMCC = networkUsed.substring(0, 3);
            if (mSensitiveNumbersMap.containsKey(networkMCC)) {
                for (String num : mSensitiveNumbersMap.get(networkMCC)) {
                    if (PhoneNumberUtils.compare(numberToCheck, num)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
