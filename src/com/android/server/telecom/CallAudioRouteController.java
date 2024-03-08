package com.android.server.telecom;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.telecom.CallAudioState;

import com.android.internal.util.IndentingPrintWriter;

public class CallAudioRouteController implements CallAudioRouteAdapter {
    private Handler mHandler;

    public CallAudioRouteController() {
        HandlerThread handlerThread = new HandlerThread(this.getClass().getSimpleName());
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
    }
    @Override
    public void initialize() {
    }

    @Override
    public void sendMessageWithSessionInfo(int message) {
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg) {

    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, String data) {

    }

    @Override
    public void sendMessage(int message, Runnable r) {

    }

    @Override
    public void setCallAudioManager(CallAudioManager callAudioManager) {
    }

    @Override
    public CallAudioState getCurrentCallAudioState() {
        return null;
    }

    @Override
    public boolean isHfpDeviceAvailable() {
        return false;
    }

    @Override
    public Handler getAdapterHandler() {
        return mHandler;
    }

    @Override
    public void dump(IndentingPrintWriter pw) {

    }
}
