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
 * limitations under the License
 */

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.server.telecom.testapps.localvoicemail.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * An adapter for the RecyclerView that displays a list of voicemail files.
 */
public class VoicemailAdapter extends RecyclerView.Adapter<VoicemailAdapter.VoicemailViewHolder> {

    private static final java.lang.String LOG_TAG = "VoicemailAdapter";
    private final java.util.List<File> mVoicemailFiles;
    private MediaPlayer mMediaPlayer;

    /**
     * Provides a reference to the views for each data item.
     */
    public static class VoicemailViewHolder extends RecyclerView.ViewHolder {
        public final TextView fileNameTextView;

        public VoicemailViewHolder(View view) {
            super(view);
            fileNameTextView = view.findViewById(R.id.voicemail_file_name);
        }
    }

    /**
     * Constructs a new VoicemailAdapter.
     *
     * @param voicemailFiles The list of voicemail files to display.
     */
    public VoicemailAdapter(java.util.List<File> voicemailFiles) {
        mVoicemailFiles = voicemailFiles;
    }

    @NonNull
    @java.lang.Override
    public VoicemailViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.voicemail_list_item, parent, false);
        return new VoicemailViewHolder(view);
    }

    @java.lang.Override
    public void onBindViewHolder(@NonNull VoicemailViewHolder holder, int position) {
        File voicemailFile = mVoicemailFiles.get(position);
        holder.fileNameTextView.setText(voicemailFile.getName());

        // Set a click listener to play the voicemail.
        holder.itemView.setOnClickListener(v -> {
            playVoicemail(v.getContext(), voicemailFile);
        });
    }

    @java.lang.Override
    public int getItemCount() {
        return mVoicemailFiles.size();
    }

    /**
     * Plays the selected voicemail file.
     *
     * @param context The context to use for playing media.
     * @param file    The voicemail file to play.
     */
    private void playVoicemail(Context context, File file) {
        // Stop and release any existing media player.
        releaseMediaPlayer();

        mMediaPlayer = new MediaPlayer();
        FileInputStream fis = null;

        try {
            fis = new FileInputStream(file);
            mMediaPlayer.setDataSource(fis.getFD());
            // Release the player once playback is complete.
            mMediaPlayer.setOnCompletionListener(mp -> releaseMediaPlayer());
            mMediaPlayer.prepare();
            mMediaPlayer.start();
            Toast.makeText(context, "Playing: " + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to play voicemail", e);
            Toast.makeText(context, "Error playing file", Toast.LENGTH_SHORT).show();
            releaseMediaPlayer();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Stops and releases the media player resources.
     */
    public void releaseMediaPlayer() {
        if (mMediaPlayer != null) {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.stop();
            }
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }
}