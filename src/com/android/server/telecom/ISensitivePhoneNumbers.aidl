/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.telecom;

/** @hide */
interface ISensitivePhoneNumbers {
    const String SERVICE_NAME = "lineagesensitivephone";

    boolean isSensitiveNumber(String number, int subId);
}

