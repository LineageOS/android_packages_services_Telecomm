/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.telecom.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.telecom.Log;
import android.util.LocalLog;

import com.android.internal.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class BluetoothDeviceManager {
    private final BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    Log.startSession("BMSL.oSC");
                    try {
                        synchronized (mLock) {
                            String logString;
                            if (profile == BluetoothProfile.HEADSET) {
                                mBluetoothHeadset = (BluetoothHeadset) proxy;
                                logString = "Got BluetoothHeadset: " + mBluetoothHeadset;
                            } else if (profile == BluetoothProfile.HEARING_AID) {
                                mBluetoothHearingAid = (BluetoothHearingAid) proxy;
                                logString = "Got BluetoothHearingAid: "
                                        + mBluetoothHearingAid;
                            } else {
                                logString = "Connected to non-requested bluetooth service." +
                                        " Not changing bluetooth headset.";
                            }
                            Log.i(BluetoothDeviceManager.this, logString);
                            mLocalLog.log(logString);
                        }
                    } finally {
                        Log.endSession();
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    Log.startSession("BMSL.oSD");
                    try {
                        synchronized (mLock) {
                            LinkedHashMap<String, BluetoothDevice> lostServiceDevices;
                            String logString;
                            if (profile == BluetoothProfile.HEADSET) {
                                mBluetoothHeadset = null;
                                lostServiceDevices = mHfpDevicesByAddress;
                                mBluetoothRouteManager.onActiveDeviceChanged(null, false);
                                logString = "Lost BluetoothHeadset service. " +
                                        "Removing all tracked devices";
                            } else if (profile == BluetoothProfile.HEARING_AID) {
                                mBluetoothHearingAid = null;
                                logString = "Lost BluetoothHearingAid service. " +
                                        "Removing all tracked devices.";
                                lostServiceDevices = mHearingAidDevicesByAddress;
                                mBluetoothRouteManager.onActiveDeviceChanged(null, true);
                            } else {
                                return;
                            }
                            Log.i(BluetoothDeviceManager.this, logString);
                            mLocalLog.log(logString);

                            List<BluetoothDevice> devicesToRemove = new LinkedList<>(
                                    lostServiceDevices.values());
                            lostServiceDevices.clear();
                            for (BluetoothDevice device : devicesToRemove) {
                                mBluetoothRouteManager.onDeviceLost(device.getAddress());
                            }
                        }
                    } finally {
                        Log.endSession();
                    }
                }
           };

    private final LinkedHashMap<String, BluetoothDevice> mHfpDevicesByAddress =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, BluetoothDevice> mHearingAidDevicesByAddress =
            new LinkedHashMap<>();
    private final LinkedHashMap<BluetoothDevice, Long> mHearingAidDeviceSyncIds =
            new LinkedHashMap<>();
    private final LocalLog mLocalLog = new LocalLog(20);

    // This lock only protects internal state -- it doesn't lock on anything going into Telecom.
    private final Object mLock = new Object();

    private BluetoothRouteManager mBluetoothRouteManager;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothHearingAid mBluetoothHearingAid;
    private BluetoothDevice mBluetoothHearingAidActiveDeviceCache;
    private BluetoothAdapter mBluetoothAdapter;

    public BluetoothDeviceManager(Context context, BluetoothAdapter bluetoothAdapter) {
        if (bluetoothAdapter != null) {
            mBluetoothAdapter = bluetoothAdapter;
            bluetoothAdapter.getProfileProxy(context, mBluetoothProfileServiceListener,
                    BluetoothProfile.HEADSET);
            bluetoothAdapter.getProfileProxy(context, mBluetoothProfileServiceListener,
                    BluetoothProfile.HEARING_AID);
        }
    }

    public void setBluetoothRouteManager(BluetoothRouteManager brm) {
        mBluetoothRouteManager = brm;
    }

    public int getNumConnectedDevices() {
        synchronized (mLock) {
            return mHfpDevicesByAddress.size() + mHearingAidDevicesByAddress.size();
        }
    }

    public Collection<BluetoothDevice> getConnectedDevices() {
        synchronized (mLock) {
            ArrayList<BluetoothDevice> result = new ArrayList<>(mHfpDevicesByAddress.values());
            result.addAll(mHearingAidDevicesByAddress.values());
            return Collections.unmodifiableCollection(result);
        }
    }

    // Same as getConnectedDevices except it filters out the hearing aid devices that are linked
    // together by their hiSyncId.
    public Collection<BluetoothDevice> getUniqueConnectedDevices() {
        ArrayList<BluetoothDevice> result;
        synchronized (mLock) {
            result = new ArrayList<>(mHfpDevicesByAddress.values());
        }
        Set<Long> seenHiSyncIds = new LinkedHashSet<>();
        // Add the left-most active device to the seen list so that we match up with the list
        // generated in BluetoothRouteManager.
        for (BluetoothDevice device : mBluetoothAdapter.getActiveDevices(
                    BluetoothProfile.HEARING_AID)) {
            if (device != null) {
                result.add(device);
                seenHiSyncIds.add(mHearingAidDeviceSyncIds.getOrDefault(device, -1L));
                break;
            }
        }
        synchronized (mLock) {
            for (BluetoothDevice d : mHearingAidDevicesByAddress.values()) {
                long hiSyncId = mHearingAidDeviceSyncIds.getOrDefault(d, -1L);
                if (seenHiSyncIds.contains(hiSyncId)) {
                    continue;
                }
                result.add(d);
                seenHiSyncIds.add(hiSyncId);
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    public BluetoothHeadset getBluetoothHeadset() {
        return mBluetoothHeadset;
    }

    public BluetoothAdapter getBluetoothAdapter() {
        return mBluetoothAdapter;
    }

    public BluetoothHearingAid getBluetoothHearingAid() {
        return mBluetoothHearingAid;
    }

    public void setHeadsetServiceForTesting(BluetoothHeadset bluetoothHeadset) {
        mBluetoothHeadset = bluetoothHeadset;
    }

    public void setHearingAidServiceForTesting(BluetoothHearingAid bluetoothHearingAid) {
        mBluetoothHearingAid = bluetoothHearingAid;
    }

    void onDeviceConnected(BluetoothDevice device, boolean isHearingAid) {
        mLocalLog.log("Device connected -- address: " + device.getAddress() + " isHeadingAid: "
                + isHearingAid);
        synchronized (mLock) {
            LinkedHashMap<String, BluetoothDevice> targetDeviceMap;
            if (isHearingAid) {
                if (mBluetoothHearingAid == null) {
                    Log.w(this, "Hearing aid service null when receiving device added broadcast");
                    return;
                }
                long hiSyncId = mBluetoothHearingAid.getHiSyncId(device);
                mHearingAidDeviceSyncIds.put(device, hiSyncId);
                targetDeviceMap = mHearingAidDevicesByAddress;
            } else {
                if (mBluetoothHeadset == null) {
                    Log.w(this, "Headset service null when receiving device added broadcast");
                    return;
                }
                targetDeviceMap = mHfpDevicesByAddress;
            }
            if (!targetDeviceMap.containsKey(device.getAddress())) {
                targetDeviceMap.put(device.getAddress(), device);
                mBluetoothRouteManager.onDeviceAdded(device.getAddress());
            }
        }
    }

    void onDeviceDisconnected(BluetoothDevice device, boolean isHearingAid) {
        mLocalLog.log("Device disconnected -- address: " + device.getAddress() + " isHeadingAid: "
                + isHearingAid);
        synchronized (mLock) {
            LinkedHashMap<String, BluetoothDevice> targetDeviceMap;
            if (isHearingAid) {
                mHearingAidDeviceSyncIds.remove(device);
                targetDeviceMap = mHearingAidDevicesByAddress;
            } else {
                targetDeviceMap = mHfpDevicesByAddress;
            }
            if (targetDeviceMap.containsKey(device.getAddress())) {
                targetDeviceMap.remove(device.getAddress());
                mBluetoothRouteManager.onDeviceLost(device.getAddress());
            }
        }
    }

    public void disconnectAudio() {
        for (BluetoothDevice device: mBluetoothAdapter.getActiveDevices(
                    BluetoothProfile.HEARING_AID)) {
            if (device != null) {
                mBluetoothAdapter.setActiveDevice(null, BluetoothAdapter.ACTIVE_DEVICE_ALL);
            }
        }
        disconnectSco();
    }

    public void disconnectSco() {
        if (mBluetoothHeadset == null) {
            Log.w(this, "Trying to disconnect audio but no headset service exists.");
        } else {
            mBluetoothHeadset.disconnectAudio();
        }
    }

    // Connect audio to the bluetooth device at address, checking to see whether it's a hearing aid
    // or a HFP device, and using the proper BT API.
    public boolean connectAudio(String address) {
        if (mHearingAidDevicesByAddress.containsKey(address)) {
            if (mBluetoothHearingAid == null) {
                Log.w(this, "Attempting to turn on audio when the hearing aid service is null");
                return false;
            }
            return mBluetoothAdapter.setActiveDevice(
                    mHearingAidDevicesByAddress.get(address),
                    BluetoothAdapter.ACTIVE_DEVICE_ALL);
        } else if (mHfpDevicesByAddress.containsKey(address)) {
            BluetoothDevice device = mHfpDevicesByAddress.get(address);
            if (mBluetoothHeadset == null) {
                Log.w(this, "Attempting to turn on audio when the headset service is null");
                return false;
            }
            boolean success = mBluetoothAdapter.setActiveDevice(device,
                BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL);
            if (!success) {
                Log.w(this, "Couldn't set active device to %s", address);
                return false;
            }
            if (!mBluetoothHeadset.isAudioOn()) {
                return mBluetoothHeadset.connectAudio();
            }
            return true;
        } else {
            Log.w(this, "Attempting to turn on audio for a disconnected device");
            return false;
        }
    }

    public void cacheHearingAidDevice() {
        for (BluetoothDevice device : mBluetoothAdapter.getActiveDevices(
                    BluetoothProfile.HEARING_AID)) {
            if (device != null) {
                mBluetoothHearingAidActiveDeviceCache = device;
            }
        }
    }

    public void restoreHearingAidDevice() {
        if (mBluetoothHearingAidActiveDeviceCache != null) {
            mBluetoothAdapter.setActiveDevice(mBluetoothHearingAidActiveDeviceCache,
                    BluetoothAdapter.ACTIVE_DEVICE_ALL);
            mBluetoothHearingAidActiveDeviceCache = null;
        }
    }

    public void dump(IndentingPrintWriter pw) {
        mLocalLog.dump(pw);
    }
}
