package com.android.server.telecom.testapps.localvoicemail;

/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Process;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * A helper class that records audio from an AudioRecord instance to a file on disk,
 * saving it in the WAVE file format (.wav).
 */
public class WavAudioRecorder {
    private static final String TAG = "WavAudioRecorder";

    // Audio source parameters (must match the provided AudioRecord instance)
    private static final int SAMPLE_RATE_IN_HZ = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int NUM_CHANNELS = 1; // Based on CHANNEL_IN_MONO

    // Calculate buffer size
    private static final int BUFFER_SIZE_BYTES = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);

    private AudioRecord mAudioRecord;
    private Thread mRecordingThread;
    private volatile boolean mIsRecording = false;

    /**
     * Constructor for WavAudioRecorder.
     */
    public WavAudioRecorder() {
    }

    /**
     * Starts the audio recording process.
     * Audio data will be saved to the specified file path as a WAVE file.
     *
     * @param sourceAudioRecord The AudioRecord instance to pull raw PCM data from. It must be
     *                          configured with the same parameters as this class
     *                          (44100Hz, MONO, PCM_16BIT).
     * @param outputFilePath    The absolute path to the file where the WAVE data will be saved.
     *                          Consider using context.getFilesDir() or
     *                          context.getExternalFilesDir(null) for app-specific storage.
     */
    public void startRecording(AudioRecord sourceAudioRecord, String outputFilePath) {
        if (mIsRecording) {
            Log.w(TAG, "Recording is already in progress.");
            return;
        }

        this.mAudioRecord = sourceAudioRecord;

        mIsRecording = true;

        mRecordingThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            FileOutputStream fos = null;
            long totalAudioLen = 0;
            boolean recordingSuccessfullyStarted = false;

            try {
                fos = new FileOutputStream(outputFilePath);
                // Write a placeholder WAVE header
                writeWavHeader(fos, 0, 0);

                mAudioRecord.startRecording();
                recordingSuccessfullyStarted = true;
                Log.i(TAG, "Recording started. Writing to: " + outputFilePath);

                byte[] buffer = new byte[BUFFER_SIZE_BYTES];
                while (mIsRecording) {
                    int bytesRead = mAudioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead);
                        totalAudioLen += bytesRead;
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "Error reading from AudioRecord: " + bytesRead);
                        break;
                    }
                }
                Log.i(TAG, "Recording loop finished. Total bytes read: " + totalAudioLen);

            } catch (IOException | IllegalStateException e) {
                Log.e(TAG, "Exception during recording.", e);
            } finally {
                mIsRecording = false;

                // Close the output stream
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing FileOutputStream.", e);
                    }
                }

                // Update the WAVE header with the final file size
                try {
                    updateWavHeader(outputFilePath, totalAudioLen);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to update WAV header.", e);
                }

                // Clean up AudioRecord
                if (mAudioRecord != null) {
                    if (recordingSuccessfullyStarted && mAudioRecord.getRecordingState()
                            == AudioRecord.RECORDSTATE_RECORDING) {
                        mAudioRecord.stop();
                    }
                    mAudioRecord.release();
                }
                mAudioRecord = null;
                Log.i(TAG, "Recording thread finished execution.");
            }
        }, "AudioRecordingThread");

        mRecordingThread.start();
    }

    /**
     * Stops the audio recording process.
     * This method will signal the recording thread to stop and wait for it to finish.
     */
    public void stopRecording() {
        if (!mIsRecording && (mRecordingThread == null || !mRecordingThread.isAlive())) {
            Log.i(TAG, "Recording not in progress or already stopped.");
            return;
        }

        mIsRecording = false; // Signal the thread to stop its loop.

        if (mRecordingThread != null) {
            try {
                mRecordingThread.join(2000); // Wait for the thread to finish gracefully.
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for recording thread to finish.", e);
                Thread.currentThread().interrupt();
            }
            if (mRecordingThread.isAlive()) {
                Log.e(TAG, "Recording thread did not finish. Potential resource leak.");
            }
        }
        mRecordingThread = null;
        Log.i(TAG, "Stop recording request processed.");
    }

    /**
     * Writes the 44-byte WAVE file header.
     *
     * @param out           The FileOutputStream to write to.
     * @param totalAudioLen The length of the audio data chunk (e.g., file size - 44).
     * @param totalDataLen  The length of the total data (e.g., file size - 8).
     */
    private void writeWavHeader(FileOutputStream out, long totalAudioLen, long totalDataLen)
            throws IOException {
        long sampleRate = mAudioRecord.getSampleRate();
        long byteRate = sampleRate * mAudioRecord.getChannelCount() * BITS_PER_SAMPLE / 8;
        byte[] header = new byte[44];

        header[0] = 'R';  // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // 2 bytes: format = 1 for PCM
        header[21] = 0;
        header[22] = (byte) NUM_CHANNELS;
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (NUM_CHANNELS * BITS_PER_SAMPLE / 8);  // block align
        header[33] = 0;
        header[34] = (byte) BITS_PER_SAMPLE;
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }

    /**
     * Updates the WAVE file header with the final file size.
     *
     * @param filePath      The path to the WAVE file.
     * @param totalAudioLen The total size of the recorded audio data in bytes.
     */
    private void updateWavHeader(String filePath, long totalAudioLen) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "rw")) {
            long totalDataLen = totalAudioLen + 36; // 36 bytes for header fields before data
            byte[] headerUpdate = new byte[4];

            // Seek to the ChunkSize field (offset 4) and write the total data length
            raf.seek(4);
            headerUpdate[0] = (byte) (totalDataLen & 0xff);
            headerUpdate[1] = (byte) ((totalDataLen >> 8) & 0xff);
            headerUpdate[2] = (byte) ((totalDataLen >> 16) & 0xff);
            headerUpdate[3] = (byte) ((totalDataLen >> 24) & 0xff);
            raf.write(headerUpdate);

            // Seek to the Subchunk2Size field (offset 40) and write the audio data length
            raf.seek(40);
            headerUpdate[0] = (byte) (totalAudioLen & 0xff);
            headerUpdate[1] = (byte) ((totalAudioLen >> 8) & 0xff);
            headerUpdate[2] = (byte) ((totalAudioLen >> 16) & 0xff);
            headerUpdate[3] = (byte) ((totalAudioLen >> 24) & 0xff);
            raf.write(headerUpdate);

            Log.i(TAG, "WAV header updated. File size: " + raf.length() + " bytes.");
        }
    }
}