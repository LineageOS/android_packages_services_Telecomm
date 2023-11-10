package com.android.server.telecom;

import android.os.Handler;
import android.telecom.CallAudioState;

import com.android.internal.util.IndentingPrintWriter;

public interface CallAudioRouteAdapter {
    void initialize();
    void sendMessageWithSessionInfo(int message);
    void sendMessageWithSessionInfo(int message, int arg);
    void sendMessageWithSessionInfo(int message, int arg, String data);
    void sendMessage(int message, Runnable r);
    void setCallAudioManager(CallAudioManager callAudioManager);
    CallAudioState getCurrentCallAudioState();
    boolean isHfpDeviceAvailable();
    Handler getAdapterHandler();
    void dump(IndentingPrintWriter pw);
}
