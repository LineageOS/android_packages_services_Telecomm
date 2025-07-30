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

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.server.telecom.testapps.localvoicemail.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Displays a list of local voicemail recordings stored in the app's private data directory.
 */
public class VoicemailListActivity extends AppCompatActivity {

    private VoicemailAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private TextView mEmptyView;
    private final java.util.List<File> mVoicemailFiles = new ArrayList<>();

    @java.lang.Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voicemail_list);

        mRecyclerView = findViewById(R.id.voicemail_recycler_view);
        mEmptyView = findViewById(R.id.empty_view);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new VoicemailAdapter(mVoicemailFiles);
        mRecyclerView.setAdapter(mAdapter);
    }

    @java.lang.Override
    protected void onResume() {
        super.onResume();
        loadVoicemailFiles();
    }

    @java.lang.Override
    protected void onDestroy() {
        super.onDestroy();
        // Release the media player resources to avoid leaks.
        if (mAdapter != null) {
            mAdapter.releaseMediaPlayer();
        }
    }

    /**
     * Loads the list of voicemail files from the application's internal storage.
     * This method scans the directory returned by {@link #getFilesDir()} for any files
     * and updates the RecyclerView to display them.
     */
    private void loadVoicemailFiles() {
        mVoicemailFiles.clear();
        File dataDir = getFilesDir();

        File[] files = dataDir.listFiles();
        if (files != null && files.length > 0) {
            mVoicemailFiles.addAll(Arrays.asList(files));
            mEmptyView.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
        } else {
            mEmptyView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
        }

        mAdapter.notifyDataSetChanged();
    }
}