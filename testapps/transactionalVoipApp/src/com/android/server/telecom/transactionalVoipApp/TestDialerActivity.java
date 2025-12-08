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

package com.android.server.telecom.transactionalVoipApp;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog.Calls;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test dialer to verify voip call log functionality
 */
public class TestDialerActivity extends AppCompatActivity {
    private static final String TAG = TestDialerActivity.class.getSimpleName();
    private static final int REQUEST_CODE_SET_DEFAULT_DIALER = 1;

    private RecyclerView mCallLogRecyclerView;
    private CallLogAdapter mCallLogAdapter;
    private final Map<CallLogItem, Uri> mCallLogUriMap = new HashMap<>();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    // Read call log permission handling
    private final ActivityResultLauncher<String> mRequestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                if (isGranted) {
                    // Permission is granted.
                    loadCallLog();
                } else {
                    // Tell the user that the feature is unavailable because the feature requires
                    // a permission that the user has denied.
                    Toast.makeText(this, "Permission to read call log denied.",
                            Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.testdialer_main);

        findViewById(R.id.back_to_voip_main).setOnClickListener(v -> startVoipMainActivity());
        // Set up call log UI
        setupCallLogRecyclerView();
        checkAndLoadCallLog();
    }

    private void setupCallLogRecyclerView() {
        mCallLogRecyclerView = findViewById(R.id.call_log_recycler_view);
        mCallLogAdapter = new CallLogAdapter(this);
        mCallLogRecyclerView.setAdapter(mCallLogAdapter);
        mCallLogRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void checkAndLoadCallLog() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadCallLog();
        } else {
            // Directly ask for the permission. The registered ActivityResultCallback gets the
            // result.
            mRequestPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG);
        }
    }

    private void loadCallLog() {
        // Run the query on a background thread and post the result back to the main thread.
        mExecutor.execute(() -> {
            List<CallLogItem> callLogItems = queryCallLog();
            runOnUiThread(() -> mCallLogAdapter.submitList(callLogItems));
        });
    }

    private List<CallLogItem> queryCallLog() {
        List<CallLogItem> callLogItems = new ArrayList<>();
        String[] projection = new String[]{
                Calls._ID,
                Calls.CACHED_NAME,
                Calls.NUMBER,
                Calls.UUID,
                Calls.DATE
        };

        ContentResolver resolver = getContentResolver();
        // 1. Create a bundle for the query arguments.
        Bundle queryArgs = new Bundle();
        // 2. Add the limit to the bundle.
        queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, 25);
        // 3. Add the sort order to the bundle.
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{ Calls._ID });
        queryArgs.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        // 4. Add the selection to the bundle.
        final String selection = "uuid IS NOT NULL AND " + Calls.PHONE_ACCOUNT_COMPONENT_NAME
                + " like '" + Utils.PKG_NAME + "%'";
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
        // Only query the voip calls for the purpose of this test app
        try (Cursor cursor = resolver.query(
                Calls.CONTENT_URI_WITH_VOIP_CALLS,
                projection,
                queryArgs,
                null /* CancellationSignal */
        )) {
            if (cursor != null) {
                int idColumnIndex = cursor.getColumnIndexOrThrow(Calls._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(Calls.CACHED_NAME);
                int numberColumn = cursor.getColumnIndexOrThrow(Calls.NUMBER);
                int dateColumn = cursor.getColumnIndexOrThrow(Calls.DATE);
                int uuidColumn = cursor.getColumnIndexOrThrow(Calls.UUID);

                while (cursor.moveToNext()) {
                    int callIdIndex = cursor.getInt(idColumnIndex);
                    String name = cursor.getString(nameColumn);
                    String number = cursor.getString(numberColumn);
                    String uuid = cursor.getString(uuidColumn);
                    String displayName = (name == null || name.isEmpty()) ? uuid : name;

                    CallLogItem callLogItem = new CallLogItem(displayName, number,
                            cursor.getLong(dateColumn));
                    // Store the uri mapping to the appropriate call log entry
                    Uri uri = ContentUris.withAppendedId(Calls.CONTENT_URI_WITH_VOIP_CALLS,
                            callIdIndex);
                    mCallLogUriMap.put(callLogItem, uri);
                    callLogItems.add(callLogItem);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e, "Error querying call log");
        }
        return callLogItems;
    }

    private void startVoipMainActivity() {
        Intent intent = new Intent(getApplicationContext(), VoipAppMainActivity.class);
        startActivity(intent);
    }

    /* Set up dependencies for RecyclerView */

    /* Data class to hold information for a single call log entry. */
    public static class CallLogItem {
        public final String mDisplayName;
        public final String mNumber;
        public final long mDate;

        public CallLogItem(String displayName, String number, long date) {
            this.mDisplayName = displayName;
            this.mNumber = number;
            this.mDate = date;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CallLogItem that = (CallLogItem) o;
            return mDate == that.mDate && Objects.equals(mNumber, that.mNumber) &&
                    Objects.equals(mDisplayName, that.mDisplayName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDisplayName, mNumber, mDate);
        }
    }

    /**
     * RecyclerView Adapter for displaying call log items.
     */
    public class CallLogAdapter extends ListAdapter<CallLogItem,
            CallLogAdapter.CallLogViewHolder> {

        private final Context mContext;
        public CallLogAdapter(Context context) {
            super(new CallLogDiffCallback());
            mContext = context;
        }

        @NonNull
        @Override
        public CallLogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.call_log_item, parent, false);
            return new CallLogViewHolder(view, mContext);
        }

        @Override
        public void onBindViewHolder(@NonNull CallLogViewHolder holder, int position) {
            CallLogItem callLogItem = getItem(position);
            Uri uri = mCallLogUriMap.get(callLogItem);
            holder.bind(uri, callLogItem);
        }

        /**
         * ViewHolder for a single call log item.
         */
        public static class CallLogViewHolder extends RecyclerView.ViewHolder {
            private final Context mContext;
            private final TextView idTextView;
            private final TextView numberTextView;
            private final TextView dateTextView;
            private final Button callbackButton;

            public CallLogViewHolder(@NonNull View itemView, Context context) {
                super(itemView);
                mContext = context;
                idTextView = itemView.findViewById(R.id.call_log_id);
                numberTextView = itemView.findViewById(R.id.call_log_number);
                dateTextView = itemView.findViewById(R.id.call_log_date);
                callbackButton = itemView.findViewById(R.id.callback_button);
            }

            public void bind(Uri uri, CallLogItem item) {
                idTextView.setText(item.mDisplayName != null ? item.mDisplayName : "Unknown");
                numberTextView.setText(item.mNumber);

                String date = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                        .format(new Date(item.mDate));
                dateTextView.setText(date);
                callbackButton.setOnClickListener(v -> placeCall(uri));
            }
            private void placeCall(Uri uri) {
                final TelecomManager telecomManager =
                        (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
                Log.i(TAG, "Attempting callback with " + uri);
                telecomManager.placeCall(uri, new Bundle());
            }
        }
    }

    /**
     * DiffUtil.ItemCallback for efficiently updating the list.
     */
    public static class CallLogDiffCallback extends DiffUtil.ItemCallback<CallLogItem> {
        @Override
        public boolean areItemsTheSame(@NonNull CallLogItem oldItem, @NonNull CallLogItem newItem) {
            return oldItem.mDate == newItem.mDate
                    && oldItem.mDisplayName.equals(newItem.mDisplayName)
                    && oldItem.mNumber.equals(newItem.mNumber);
        }

        @Override
        public boolean areContentsTheSame(@NonNull CallLogItem oldItem,
                @NonNull CallLogItem newItem) {
            return oldItem.equals(newItem);
        }
    }
}
