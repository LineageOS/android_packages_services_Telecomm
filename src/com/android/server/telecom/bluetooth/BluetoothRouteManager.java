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

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.telecom.Log;

import com.android.internal.os.SomeArgs;
import com.android.server.telecom.AudioRoute;

public class BluetoothRouteManager {
    private final BluetoothDeviceManager mDeviceManager;

    public BluetoothRouteManager(BluetoothDeviceManager deviceManager) {
        mDeviceManager = deviceManager;
    }

    /**
     * Returns whether there is a BT device available to route audio to.
     * @return true if there is a device, false otherwise.
     */
    public boolean isBluetoothAvailable() {
        return mDeviceManager.getNumConnectedDevices() > 0;
    }

    public boolean isWatch(BluetoothDevice device) {
        if (device == null) {
            Log.i(this, "isWatch: device is null. Returning false");
            return false;
        }

        BluetoothClass deviceClass = device.getBluetoothClass();
        if (deviceClass != null && deviceClass.getDeviceClass()
                == BluetoothClass.Device.WEARABLE_WRIST_WATCH) {
            Log.i(this, "isWatch: bluetooth class component is a WEARABLE_WRIST_WATCH.");
            return true;
        }

        // Check metadata
        byte[] deviceType = device.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE);
        if (deviceType == null) {
            return false;
        }
        String deviceTypeStr = new String(deviceType);
        if (deviceTypeStr.equals(BluetoothDevice.DEVICE_TYPE_WATCH)) {
            Log.i(this, "isWatch: bluetooth device type is DEVICE_TYPE_WATCH.");
            return true;
        }

        return false;
    }

    public boolean isInbandRingEnabled(@AudioRoute.AudioRouteType int audioRouteType,
            BluetoothDevice bluetoothDevice) {
        return mDeviceManager.isInbandRingEnabled(audioRouteType, bluetoothDevice);
    }

    public BluetoothDeviceManager getDeviceManager() {
        return mDeviceManager;
    }
}
