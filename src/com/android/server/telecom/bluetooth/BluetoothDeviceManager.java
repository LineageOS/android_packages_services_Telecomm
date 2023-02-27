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
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.audio.common.AudioDevice;
import android.telecom.Log;
import android.util.LocalLog;

import com.android.internal.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class BluetoothDeviceManager {

    public static final int DEVICE_TYPE_HEADSET = 0;
    public static final int DEVICE_TYPE_HEARING_AID = 1;
    public static final int DEVICE_TYPE_LE_AUDIO = 2;

    private BluetoothLeAudio.Callback mLeAudioCallbacks =
        new BluetoothLeAudio.Callback() {
            @Override
            public void onCodecConfigChanged(int groupId, BluetoothLeAudioCodecStatus status) {}
            @Override
            public void onGroupStatusChanged(int groupId, int groupStatus) {}
            @Override
            public void onGroupNodeAdded(BluetoothDevice device, int groupId) {
                Log.i(this, device.getAddress() + " group added " + groupId);
                if (device == null || groupId == BluetoothLeAudio.GROUP_ID_INVALID) {
                    Log.w(this, "invalid parameter");
                    return;
                }

                synchronized (mLock) {
                    mGroupsByDevice.put(device, groupId);
                }
            }
            @Override
            public void onGroupNodeRemoved(BluetoothDevice device, int groupId) {
                if (device == null || groupId == BluetoothLeAudio.GROUP_ID_INVALID) {
                    Log.w(this, "invalid parameter");
                    return;
                }

                synchronized (mLock) {
                    mGroupsByDevice.remove(device);
                }
            }
        };

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
                            } else if (profile == BluetoothProfile.LE_AUDIO) {
                                mBluetoothLeAudioService = (BluetoothLeAudio) proxy;
                                logString = "Got BluetoothLeAudio: "
                                        + mBluetoothLeAudioService;
                                if (!mLeAudioCallbackRegistered) {
                                    mBluetoothLeAudioService.registerCallback(
                                                mExecutor, mLeAudioCallbacks);
                                    mLeAudioCallbackRegistered = true;
                                }
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
                                mBluetoothRouteManager.onActiveDeviceChanged(null,
                                        DEVICE_TYPE_HEADSET);
                                logString = "Lost BluetoothHeadset service. " +
                                        "Removing all tracked devices";
                            } else if (profile == BluetoothProfile.HEARING_AID) {
                                mBluetoothHearingAid = null;
                                logString = "Lost BluetoothHearingAid service. " +
                                        "Removing all tracked devices.";
                                lostServiceDevices = mHearingAidDevicesByAddress;
                                mBluetoothRouteManager.onActiveDeviceChanged(null,
                                        DEVICE_TYPE_HEARING_AID);
                            } else if (profile == BluetoothProfile.LE_AUDIO) {
                                mBluetoothLeAudioService = null;
                                logString = "Lost BluetoothLeAudio service. " +
                                        "Removing all tracked devices.";
                                lostServiceDevices = mLeAudioDevicesByAddress;
                                mBluetoothRouteManager.onActiveDeviceChanged(null,
                                        DEVICE_TYPE_LE_AUDIO);
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
    private final LinkedHashMap<String, BluetoothDevice> mLeAudioDevicesByAddress =
            new LinkedHashMap<>();
    private final LinkedHashMap<BluetoothDevice, Integer> mGroupsByDevice =
            new LinkedHashMap<>();
    private int mGroupIdActive = BluetoothLeAudio.GROUP_ID_INVALID;
    private int mGroupIdPending = BluetoothLeAudio.GROUP_ID_INVALID;
    private final LocalLog mLocalLog = new LocalLog(20);

    // This lock only protects internal state -- it doesn't lock on anything going into Telecom.
    private final Object mLock = new Object();

    private BluetoothRouteManager mBluetoothRouteManager;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothHearingAid mBluetoothHearingAid;
    private boolean mLeAudioCallbackRegistered = false;
    private BluetoothLeAudio mBluetoothLeAudioService;
    private boolean mLeAudioSetAsCommunicationDevice = false;
    private String mLeAudioDevice;
    private String mHearingAidDevice;
    private String mScoDevice;
    private boolean mHearingAidSetAsCommunicationDevice = false;
    private boolean mScoSetAsCommunicationDevice = false;
    private BluetoothDevice mBluetoothHearingAidActiveDeviceCache;
    private BluetoothAdapter mBluetoothAdapter;
    private AudioManager mAudioManager;
    private Executor mExecutor;

    public BluetoothDeviceManager(Context context, BluetoothAdapter bluetoothAdapter) {
        if (bluetoothAdapter != null) {
            mBluetoothAdapter = bluetoothAdapter;
            bluetoothAdapter.getProfileProxy(context, mBluetoothProfileServiceListener,
                    BluetoothProfile.HEADSET);
            bluetoothAdapter.getProfileProxy(context, mBluetoothProfileServiceListener,
                    BluetoothProfile.HEARING_AID);
            bluetoothAdapter.getProfileProxy(context, mBluetoothProfileServiceListener,
                    BluetoothProfile.LE_AUDIO);
            mAudioManager = context.getSystemService(AudioManager.class);
            mExecutor = context.getMainExecutor();
        }
    }

    public void setBluetoothRouteManager(BluetoothRouteManager brm) {
        mBluetoothRouteManager = brm;
    }

    private List<BluetoothDevice> getLeAudioConnectedDevices() {
        synchronized (mLock) {
            // Let's get devices which are a group leaders
            ArrayList<BluetoothDevice> devices = new ArrayList<>();

            if (mGroupsByDevice.isEmpty() || mBluetoothLeAudioService == null) {
                return devices;
            }

            for (LinkedHashMap.Entry<BluetoothDevice, Integer> entry : mGroupsByDevice.entrySet()) {
                if (Objects.equals(entry.getKey(),
                        mBluetoothLeAudioService.getConnectedGroupLeadDevice(entry.getValue()))) {
                    devices.add(entry.getKey());
                }
            }
            devices.removeIf(device -> !mLeAudioDevicesByAddress.containsValue(device));
            return devices;
        }
    }

    public int getNumConnectedDevices() {
        synchronized (mLock) {
            return mHfpDevicesByAddress.size() +
                    mHearingAidDevicesByAddress.size() +
                    getLeAudioConnectedDevices().size();
        }
    }

    public Collection<BluetoothDevice> getConnectedDevices() {
        synchronized (mLock) {
            ArrayList<BluetoothDevice> result = new ArrayList<>(mHfpDevicesByAddress.values());
            result.addAll(mHearingAidDevicesByAddress.values());
            result.addAll(getLeAudioConnectedDevices());
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
        if (mBluetoothAdapter != null) {
            for (BluetoothDevice device : mBluetoothAdapter.getActiveDevices(
                        BluetoothProfile.HEARING_AID)) {
                if (device != null) {
                    result.add(device);
                    seenHiSyncIds.add(mHearingAidDeviceSyncIds.getOrDefault(device, -1L));
                    break;
                }
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

        if (mBluetoothLeAudioService != null) {
            result.addAll(getLeAudioConnectedDevices());
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

    public BluetoothLeAudio getLeAudioService() {
        return mBluetoothLeAudioService;
    }

    public void setHeadsetServiceForTesting(BluetoothHeadset bluetoothHeadset) {
        mBluetoothHeadset = bluetoothHeadset;
    }

    public void setHearingAidServiceForTesting(BluetoothHearingAid bluetoothHearingAid) {
        mBluetoothHearingAid = bluetoothHearingAid;
    }

    public void setLeAudioServiceForTesting(BluetoothLeAudio bluetoothLeAudio) {
        mBluetoothLeAudioService = bluetoothLeAudio;
        mBluetoothLeAudioService.registerCallback(mExecutor, mLeAudioCallbacks);
    }

    public static String getDeviceTypeString(int deviceType) {
        switch (deviceType) {
            case DEVICE_TYPE_LE_AUDIO:
                return "LeAudio";
            case DEVICE_TYPE_HEARING_AID:
                return "HearingAid";
            case DEVICE_TYPE_HEADSET:
                return "HFP";
            default:
                return "unknown type";
        }
    }

    void onDeviceConnected(BluetoothDevice device, int deviceType) {
        synchronized (mLock) {
            LinkedHashMap<String, BluetoothDevice> targetDeviceMap;
            if (deviceType == DEVICE_TYPE_LE_AUDIO) {
                if (mBluetoothLeAudioService == null) {
                    Log.w(this, "LE audio service null when receiving device added broadcast");
                    return;
                }
                /* Check if group is known. */
                if (!mGroupsByDevice.containsKey(device)) {
                    int groupId = mBluetoothLeAudioService.getGroupId(device);
                    /* If it is not yet assigned, then it will be provided in the callback */
                    if (groupId != BluetoothLeAudio.GROUP_ID_INVALID) {
                        mGroupsByDevice.put(device, groupId);
                    }
                }
                targetDeviceMap = mLeAudioDevicesByAddress;
            } else if (deviceType == DEVICE_TYPE_HEARING_AID) {
                if (mBluetoothHearingAid == null) {
                    Log.w(this, "Hearing aid service null when receiving device added broadcast");
                    return;
                }
                long hiSyncId = mBluetoothHearingAid.getHiSyncId(device);
                mHearingAidDeviceSyncIds.put(device, hiSyncId);
                targetDeviceMap = mHearingAidDevicesByAddress;
            } else if (deviceType == DEVICE_TYPE_HEADSET) {
                if (mBluetoothHeadset == null) {
                    Log.w(this, "Headset service null when receiving device added broadcast");
                    return;
                }
                targetDeviceMap = mHfpDevicesByAddress;
            } else {
                Log.w(this, "Device: " + device.getAddress() + " with invalid type: "
                            + getDeviceTypeString(deviceType));
                return;
            }
            if (!targetDeviceMap.containsKey(device.getAddress())) {
                targetDeviceMap.put(device.getAddress(), device);
                mBluetoothRouteManager.onDeviceAdded(device.getAddress());
            }
        }
    }

    void onDeviceDisconnected(BluetoothDevice device, int deviceType) {
        mLocalLog.log("Device disconnected -- address: " + device.getAddress() + " deviceType: "
                + deviceType);
        synchronized (mLock) {
            LinkedHashMap<String, BluetoothDevice> targetDeviceMap;
            if (deviceType == DEVICE_TYPE_LE_AUDIO) {
                targetDeviceMap = mLeAudioDevicesByAddress;
            } else if (deviceType == DEVICE_TYPE_HEARING_AID) {
                mHearingAidDeviceSyncIds.remove(device);
                targetDeviceMap = mHearingAidDevicesByAddress;
            } else if (deviceType == DEVICE_TYPE_HEADSET) {
                targetDeviceMap = mHfpDevicesByAddress;
            } else {
                Log.w(this, "Device: " + device.getAddress() + " with invalid type: "
                            + getDeviceTypeString(deviceType));
                return;
            }
            if (targetDeviceMap.containsKey(device.getAddress())) {
                targetDeviceMap.remove(device.getAddress());
                mBluetoothRouteManager.onDeviceLost(device.getAddress());
            }
        }
    }

    public void disconnectAudio() {
        disconnectScoAudio();
        clearLeAudioCommunicationDevice();
        clearHearingAidCommunicationDevice();
    }

    public void disconnectScoAudio() {
        clearScoAudioCommunicationDevice();
        if (mBluetoothHeadset == null) {
            Log.w(this, "Trying to disconnect audio but no headset service exists.");
        } else {
            mBluetoothHeadset.disconnectAudio();
        }
    }

    public boolean isLeAudioCommunicationDevice() {
        return mLeAudioSetAsCommunicationDevice;
    }

    public boolean isHearingAidSetAsCommunicationDevice() {
        return mHearingAidSetAsCommunicationDevice;
    }

    public boolean isScoSetAsCommunicationDevice() {
        return mScoSetAsCommunicationDevice;
    }

    public void clearLeAudioCommunicationDevice() {
        Log.i(this, "clearLeAudioCommunicationDevice: mLeAudioSetAsCommunicationDevice = " +
                mLeAudioSetAsCommunicationDevice + " device = " + mLeAudioDevice);
        if (!mLeAudioSetAsCommunicationDevice) {
            return;
        }
        mLeAudioSetAsCommunicationDevice = false;
        if (mLeAudioDevice != null) {
            mBluetoothRouteManager.onAudioLost(mLeAudioDevice);
            mLeAudioDevice = null;
        }

        if (mAudioManager == null) {
            Log.i(this, "clearLeAudioCommunicationDevice: mAudioManager is null");
            return;
        }

        AudioDeviceInfo audioDeviceInfo = mAudioManager.getCommunicationDevice();
        if (audioDeviceInfo != null && audioDeviceInfo.getType()
                == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            mBluetoothRouteManager.onAudioLost(audioDeviceInfo.getAddress());
            mAudioManager.clearCommunicationDevice();
        }
    }

    public void clearHearingAidCommunicationDevice() {
        Log.i(this, "clearHearingAidCommunicationDevice: mHearingAidSetAsCommunicationDevice = "
                + mHearingAidSetAsCommunicationDevice);
        if (!mHearingAidSetAsCommunicationDevice) {
            return;
        }
        mHearingAidSetAsCommunicationDevice = false;
        if (mHearingAidDevice != null) {
            mBluetoothRouteManager.onAudioLost(mHearingAidDevice);
            mHearingAidDevice = null;
        }

        if (mAudioManager == null) {
            Log.i(this, "clearHearingAidCommunicationDevice: mAudioManager is null");
            return;
        }

        AudioDeviceInfo audioDeviceInfo = mAudioManager.getCommunicationDevice();
        if (audioDeviceInfo != null && audioDeviceInfo.getType()
                == AudioDeviceInfo.TYPE_HEARING_AID) {
            mAudioManager.clearCommunicationDevice();
        }
    }

    public void clearScoAudioCommunicationDevice() {
        Log.i(this, "clearScoCommunicationDevice: mScoSetAsCommunicationDevice = "
                + mScoSetAsCommunicationDevice);
        if (!mScoSetAsCommunicationDevice) {
            return;
        }
        mScoSetAsCommunicationDevice = false;
        if (mScoDevice != null) {
            mBluetoothRouteManager.onAudioLost(mScoDevice);
            mScoDevice = null;
        }

        if (mAudioManager == null) {
            Log.i(this, "clearScoCommunicationDevice: mAudioManager is null");
            return;
        }

        AudioDeviceInfo audioDeviceInfo = mAudioManager.getCommunicationDevice();
        if (audioDeviceInfo != null && audioDeviceInfo.getType()
                == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            mAudioManager.clearCommunicationDevice();
        }
    }

    public boolean setLeAudioCommunicationDevice(BluetoothDevice leAudioDevice) {
        Log.i(this, "setLeAudioCommunicationDevice");

        // Ensure that the device being set isn't one we have set already.
        if (mLeAudioSetAsCommunicationDevice && leAudioDevice.getAddress().equals(mLeAudioDevice)) {
            Log.i(this, "No change in LE audio device.");
            return true;
        }

        if (mAudioManager == null) {
            Log.w(this, "mAudioManager is null");
            return false;
        }

        AudioDeviceInfo bleHeadset = null;
        List<AudioDeviceInfo> devices = mAudioManager.getAvailableCommunicationDevices();
        if (devices.size() == 0) {
            Log.w(this, "No communication devices available.");
            return false;
        }

        for (AudioDeviceInfo device : devices) {
            Log.i(this, " Available device type:  " + device.getType());
            // Make sure we find a device that hasn't been set already.
            if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                    && !device.getAddress().equals(mLeAudioDevice)) {
                bleHeadset = device;
                break;
            }
        }

        if (bleHeadset == null) {
            Log.w(this, "No bleHeadset device available");
            return false;
        }

        // Clear hearing aid or SCO communication device if set
        clearHearingAidCommunicationDevice();
        clearScoAudioCommunicationDevice();
        // Check if another LE audio device was set as the communication device already and clear it
        if (mLeAudioDevice != null) {
            clearLeAudioCommunicationDevice();
        }

        // Turn BLE_OUT_HEADSET ON.
        boolean result = mAudioManager.setCommunicationDevice(bleHeadset);
        if (!result) {
            Log.w(this, " Could not set bleHeadset device");
        } else {
            Log.i(this, " bleHeadset device set");
            mBluetoothRouteManager.onAudioOn(bleHeadset.getAddress());
            mLeAudioSetAsCommunicationDevice = true;
            mLeAudioDevice = bleHeadset.getAddress();
        }
        return result;
    }

    public boolean setHearingAidCommunicationDevice() {
        Log.i(this, "setHearingAidCommunicationDevice");

        if (mHearingAidSetAsCommunicationDevice) {
            Log.i(this, "mHearingAidSetAsCommunicationDevice already set");
            return true;
        }

        if (mAudioManager == null) {
            Log.w(this, "mAudioManager is null");
            return false;
        }

        AudioDeviceInfo hearingAid = null;
        List<AudioDeviceInfo> devices = mAudioManager.getAvailableCommunicationDevices();
        if (devices.size() == 0) {
            Log.w(this, "No communication devices available.");
            return false;
        }

        for (AudioDeviceInfo device : devices) {
            Log.i(this, "Available device type:  " + device.getType());
            if (device.getType() == AudioDeviceInfo.TYPE_HEARING_AID) {
                hearingAid = device;
                break;
            }
        }

        if (hearingAid == null) {
            Log.w(this, "No hearingAid device available");
            return false;
        }

        // clear LE or SCO audio communication device if set
        clearLeAudioCommunicationDevice();
        clearScoAudioCommunicationDevice();

        // Turn hearing aid ON.
        boolean result = mAudioManager.setCommunicationDevice(hearingAid);
        if (!result) {
            Log.w(this, "Could not set hearingAid device");
        } else {
            Log.i(this, "hearingAid device set");
            mHearingAidDevice = hearingAid.getAddress();
            mHearingAidSetAsCommunicationDevice = true;
        }
        return result;
    }

    public boolean setScoAudioCommunicationDevice() {
        Log.i(this, "setScoCommunicationDevice");

        if (mScoSetAsCommunicationDevice) {
            Log.i(this, "mScoSetAsCommunicationDevice already set");
            return true;
        }

        if (mAudioManager == null) {
            Log.w(this, "mAudioManager is null");
            return false;
        }

        AudioDeviceInfo scoAudio = null;
        List<AudioDeviceInfo> devices = mAudioManager.getAvailableCommunicationDevices();
        if (devices.size() == 0) {
            Log.w(this, "No communication devices available.");
            return false;
        }

        for (AudioDeviceInfo device : devices) {
            Log.i(this, "Available device type:  " + device.getType());
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                scoAudio = device;
                break;
            }
        }

        if (scoAudio == null) {
            Log.w(this, "No scoAudio device available");
            return false;
        }

        // clear LE or hearing aid audio communication device if set
        clearLeAudioCommunicationDevice();
        clearHearingAidCommunicationDevice();

        // Set SCO communication device.
        boolean result = mAudioManager.setCommunicationDevice(scoAudio);
        if (!result) {
            Log.w(this, "Could not set scoAudio device");
            return false;
        }
        mScoSetAsCommunicationDevice = true;
        mScoDevice = scoAudio.getAddress();
        return true;
    }

    // Connect audio to the bluetooth device at address, checking to see whether it's
    // le audio, hearing aid or a HFP device, and using the proper BT API.
    public boolean connectAudio(String address, boolean switchingBtDevices) {
        if (mLeAudioDevicesByAddress.containsKey(address)) {
            if (mBluetoothLeAudioService == null) {
                Log.w(this, "Attempting to turn on audio when the le audio service is null");
                return false;
            }
            BluetoothDevice device = mLeAudioDevicesByAddress.get(address);
            if (mBluetoothAdapter.setActiveDevice(
                    device, BluetoothAdapter.ACTIVE_DEVICE_ALL)) {

                /* ACTION_ACTIVE_DEVICE_CHANGED intent will trigger setting communication device.
                 * Only after receiving ACTION_ACTIVE_DEVICE_CHANGED is it known that the device
                 * that will be audio switched to is available to be chosen as communication device.
                 */
                if (!switchingBtDevices) {
                    return setLeAudioCommunicationDevice(device);
                }

                return true;
            }
            return false;
        } else if (mHearingAidDevicesByAddress.containsKey(address)) {
            if (mBluetoothHearingAid == null) {
                Log.w(this, "Attempting to turn on audio when the hearing aid service is null");
                return false;
            }
            if (mBluetoothAdapter.setActiveDevice(
                    mHearingAidDevicesByAddress.get(address),
                    BluetoothAdapter.ACTIVE_DEVICE_ALL)) {

                /* ACTION_ACTIVE_DEVICE_CHANGED intent will trigger setting communication device.
                 * Only after receiving ACTION_ACTIVE_DEVICE_CHANGED is it known that the device
                 * that will be audio switched to is available to be chosen as communication device.
                 */
                if (!switchingBtDevices) {
                    return setHearingAidCommunicationDevice();
                }

                return true;
            }
            return false;
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
            /* ACTION_ACTIVE_DEVICE_CHANGED intent will trigger setting communication device.
             * Only after receiving ACTION_ACTIVE_DEVICE_CHANGED is it known that the device that
             * will be audio switched to is available to be chosen as communication device.
             */
            if (!switchingBtDevices && !setScoAudioCommunicationDevice()) {
                // If the communication device cannot be set, we should not try connecting the SCO
                // audio device
                return false;
            }
            int scoConnectionRequest = mBluetoothHeadset.connectAudio();
            boolean scoSuccessfulConnection = scoConnectionRequest ==
                    BluetoothStatusCodes.SUCCESS || scoConnectionRequest ==
                    BluetoothStatusCodes.ERROR_AUDIO_DEVICE_ALREADY_CONNECTED;
            if (!scoSuccessfulConnection) {
                // Clear communication device if we're unable to connect to BT headset audio
                clearScoAudioCommunicationDevice();
            }
            return scoSuccessfulConnection;
        } else {
            Log.w(this, "Attempting to turn on audio for a disconnected device");
            return false;
        }
    }

    public void cacheHearingAidDevice() {
        if (mBluetoothAdapter != null) {
            for (BluetoothDevice device : mBluetoothAdapter.getActiveDevices(
                        BluetoothProfile.HEARING_AID)) {
                if (device != null) {
                    mBluetoothHearingAidActiveDeviceCache = device;
                }
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

    public boolean isInbandRingingEnabled() {
        BluetoothDevice activeDevice = mBluetoothRouteManager.getBluetoothAudioConnectedDevice();
        Log.i(this, "isInbandRingingEnabled: activeDevice: " + activeDevice);
        if (mBluetoothRouteManager.isCachedLeAudioDevice(activeDevice)) {
            if (mBluetoothLeAudioService == null) {
                Log.i(this, "isInbandRingingEnabled: no leaudio service available.");
                return false;
            }
            int groupId = mBluetoothLeAudioService.getGroupId(activeDevice);
            return mBluetoothLeAudioService.isInbandRingtoneEnabled(groupId);
        } else {
            if (mBluetoothHeadset == null) {
                Log.i(this, "isInbandRingingEnabled: no headset service available.");
                return false;
            }
            return mBluetoothHeadset.isInbandRingingEnabled();
        }
    }

    public void dump(IndentingPrintWriter pw) {
        mLocalLog.dump(pw);
    }
}
