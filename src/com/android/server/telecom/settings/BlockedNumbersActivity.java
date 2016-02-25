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

package com.android.server.telecom.settings;

import android.annotation.Nullable;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.LoaderManager;
import android.content.*;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.provider.BlockedNumberContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.android.server.telecom.R;

/**
 * Activity to manage blocked numbers using {@link BlockedNumberContract}.
 */
public class BlockedNumbersActivity extends ListActivity
        implements LoaderManager.LoaderCallbacks<Cursor>, View.OnClickListener, TextWatcher {
    private static final String[] PROJECTION = new String[] {
            BlockedNumberContract.BlockedNumbers.COLUMN_ID,
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER
    };

    private static final String SELECTION = "((" +
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER + " NOTNULL) AND (" +
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER + " != '' ))";

    private BlockedNumbersAdapter mAdapter;
    private TextView mAddButton;
    private ProgressBar mProgressBar;
    @Nullable private Button mBlockButton;

    public static void start(Context context) {
        Intent intent = new Intent(context, BlockedNumbersActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.xml.activity_blocked_numbers);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        if (!userManager.isPrimaryUser()) {
            TextView nonPrimaryUserText = (TextView) findViewById(R.id.non_primary_user);
            nonPrimaryUserText.setVisibility(View.VISIBLE);

            LinearLayout manageBlockedNumbersUi =
                    (LinearLayout) findViewById(R.id.manage_blocked_ui);
            manageBlockedNumbersUi.setVisibility(View.GONE);
            return;
        }
        mAddButton = (TextView) findViewById(R.id.add_blocked);
        mAddButton.setOnClickListener(this);

        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);

        String[] fromColumns = {BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER};
        int[] toViews = {R.id.blocked_number};
        mAdapter = new BlockedNumbersAdapter(this, R.xml.layout_blocked_number, null, fromColumns,
                toViews, 0);

        ListView listView = getListView();
        listView.setAdapter(mAdapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);

        getLoaderManager().initLoader(0, null, this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                this.finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(this, BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                PROJECTION, SELECTION, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
        mProgressBar.setVisibility(View.GONE);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
        mProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onClick(View view) {
        if (view == mAddButton) {
            showAddBlockedNumberDialog();
        }
    }

    private void showAddBlockedNumberDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.xml.add_blocked_number_dialog, null);
        final EditText editText = (EditText) dialogView.findViewById(R.id.add_blocked_number);
        editText.addTextChangedListener(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton(R.string.block_button, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        addBlockedNumber(editText.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                })
                .create();
        dialog.setOnShowListener(new AlertDialog.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface dialog) {
                        mBlockButton = ((AlertDialog) dialog)
                                .getButton(AlertDialog.BUTTON_POSITIVE);
                        mBlockButton.setEnabled(false);
                        // show keyboard
                        InputMethodManager inputMethodManager =
                                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        inputMethodManager.showSoftInput(editText,
                                InputMethodManager.SHOW_IMPLICIT);

                    }
                });
        dialog.show();
    }

    /**
     * Add blocked number if it does not exist.
     */
    private void addBlockedNumber(String number) {
        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                PROJECTION,
                BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER + "=?",
                new String[] {number},
                null);
        if (cursor == null || cursor.getCount() == 0) {
            ContentValues newValues = new ContentValues();
            newValues.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number);
            contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, newValues);
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // no-op
    }

    @Override
    public void onTextChanged(CharSequence text, int start, int before, int count) {
        if (mBlockButton != null) {
            mBlockButton.setEnabled(!TextUtils.isEmpty(text.toString().trim()));
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
        // no-op
    }
}
