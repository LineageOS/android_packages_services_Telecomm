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

package com.android.server.telecom;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.DefaultDialerManager;
import android.telecom.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

public class DefaultDialerCache {
    private static final String LOG_TAG = "DefaultDialerCache";
    @VisibleForTesting
    public final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private final DefaultDialerManagerAdapter mDefaultDialerManagerAdapter;
    private final ComponentName mSystemDialerComponentName;
    private final RoleManagerAdapter mRoleManagerAdapter;
    private final FeatureFlags mFeatureFlags;
    private final ConcurrentHashMap<Integer, String> mCurrentDefaultDialerPerUser =
            new ConcurrentHashMap<>();
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.post(() -> {
                Log.startSession("DDC.oR");
                try {
                    String packageName;
                    if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())) {
                        packageName = null;
                    } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                            && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        packageName = intent.getData().getSchemeSpecificPart();
                    } else if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                        packageName = null;
                    } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                        packageName = null;
                    } else {
                        return;
                    }

                    refreshCachesForUsersWithPackage(packageName);
                } finally {
                    Log.endSession();
                }
            });
        }
    };
    private final BroadcastReceiver mUserRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                int removedUser = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
                if (removedUser == UserHandle.USER_NULL) {
                    Log.w(LOG_TAG, "Expected EXTRA_USER_HANDLE with ACTION_USER_REMOVED");
                } else {
                    removeUserFromCache(removedUser);
                    Log.i(LOG_TAG, "Removing user %s", removedUser);
                }
            }
        }
    };
    private final ContentObserver mDefaultDialerObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            Log.startSession("DDC.oC");
            try {
                // We don't get the user ID of the user that changed here, so we'll have to
                // refresh all of the users.
                refreshCachesForUsersWithPackage(null);
            } finally {
                Log.endSession();
            }
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }
    };
    private ComponentName mOverrideSystemDialerComponentName;

    public DefaultDialerCache(Context context,
            DefaultDialerManagerAdapter defaultDialerManagerAdapter,
            RoleManagerAdapter roleManagerAdapter,
            TelecomSystem.SyncRoot lock, FeatureFlags featureFlags) {

        mContext = context;
        mDefaultDialerManagerAdapter = defaultDialerManagerAdapter;
        mRoleManagerAdapter = roleManagerAdapter;
        mFeatureFlags = featureFlags;
        Resources resources = mContext.getResources();
        mSystemDialerComponentName = new ComponentName(resources.getString(
                com.android.internal.R.string.config_defaultDialer),
                resources.getString(R.string.incall_default_class));

        IntentFilter packageIntentFilter = new IntentFilter();
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageIntentFilter.addDataScheme("package");
        packageIntentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        Context userContext = context.createContextAsUser(UserHandle.ALL, 0);
        if (mFeatureFlags.resolveHiddenDependenciesTwo()) {
            userContext.registerReceiver(mReceiver, packageIntentFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiverAsUser(mReceiver, UserHandle.ALL, packageIntentFilter, null,
                    null);
        }

        IntentFilter bootIntentFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
        if (mFeatureFlags.resolveHiddenDependenciesTwo()) {
            userContext.registerReceiver(mReceiver, bootIntentFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiverAsUser(mReceiver, UserHandle.ALL, bootIntentFilter, null, null);
        }

        IntentFilter userRemovedFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        context.registerReceiver(mUserRemovedReceiver, userRemovedFilter);

        Uri defaultDialerSetting =
                Settings.Secure.getUriFor(Settings.Secure.DIALER_DEFAULT_APPLICATION);

        if (mFeatureFlags.resolveHiddenDependenciesTwo()){
            context.getContentResolver()
                    .registerContentObserverAsUser(defaultDialerSetting, false,
                            mDefaultDialerObserver, UserHandle.ALL);
        } else {
            context.getContentResolver()
                    .registerContentObserver(defaultDialerSetting, false,
                            mDefaultDialerObserver, UserHandle.USER_ALL);
        }
    }

    public String[] getBTInCallServicePackages() {
        return mRoleManagerAdapter.getBTInCallService();
    }

    public String getDefaultDialerApplication(int userId) {
        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        if (userId < 0) {
            Log.w(LOG_TAG, "Attempting to get default dialer for a meta-user %d", userId);
            return null;
        }

        // TODO: Re-enable this when we are able to use the cache once more.  RoleManager does not
        // provide a means for being informed when the role holder changes at the current time.
        //
        //synchronized (mLock) {
        //    String defaultDialer = mCurrentDefaultDialerPerUser.get(userId);
        //    if (!TextUtils.isEmpty(defaultDialer)) {
        //        return defaultDialer;
        //    }
        //}
        return refreshCacheForUser(userId);
    }

    public String getDefaultDialerApplication() {
        return getDefaultDialerApplication(UserUtil.getUserIdFromContext(mContext, mFeatureFlags));
    }

    public void setSystemDialerComponentName(ComponentName testComponentName) {
        mOverrideSystemDialerComponentName = testComponentName;
    }

    public String getSystemDialerApplication() {
        if (mOverrideSystemDialerComponentName != null) {
            return mOverrideSystemDialerComponentName.getPackageName();
        }
        return mSystemDialerComponentName.getPackageName();
    }

    public ComponentName getSystemDialerComponent() {
        if (mOverrideSystemDialerComponentName != null) return mOverrideSystemDialerComponentName;
        return mSystemDialerComponentName;
    }

    public ComponentName getDialtactsSystemDialerComponent() {
        final Resources resources = mContext.getResources();
        return new ComponentName(getSystemDialerApplication(),
                resources.getString(R.string.dialer_default_class));
    }

    public void observeDefaultDialerApplication(Executor executor, IntConsumer observer) {
        mRoleManagerAdapter.observeDefaultDialerApp(executor, observer);
    }

    public boolean isDefaultOrSystemDialer(String packageName, int userId) {
        String defaultDialer = getDefaultDialerApplication(userId);
        return Objects.equals(packageName, defaultDialer)
                || Objects.equals(packageName, getSystemDialerApplication());
    }

    public boolean setDefaultDialer(String packageName, int userId) {
        boolean isChanged = mDefaultDialerManagerAdapter.setDefaultDialerApplication(
                mContext, packageName, userId);
        if (isChanged) {
            // Update the cache synchronously so that there is no delay in cache update.
            mCurrentDefaultDialerPerUser.put(userId, packageName == null ? "" : packageName);
        }
        return isChanged;
    }

    private String refreshCacheForUser(int userId) {
        String currentDefaultDialer =
                mRoleManagerAdapter.getDefaultDialerApp(userId);
        mCurrentDefaultDialerPerUser.put(userId, currentDefaultDialer == null ? "" :
                currentDefaultDialer);
        return currentDefaultDialer;
    }

    /**
     * Refreshes the cache for users that currently have packageName as their cached default dialer.
     * If packageName is null, refresh all caches.
     *
     * @param packageName Name of the affected package.
     */
    private void refreshCachesForUsersWithPackage(String packageName) {
        mCurrentDefaultDialerPerUser.forEach((userId, currentName) -> {
            if (packageName == null || Objects.equals(packageName, currentName)) {
                String newDefaultDialer = refreshCacheForUser(userId);
                Log.v(LOG_TAG, "Refreshing default dialer for user %d: now %s",
                        userId, newDefaultDialer);
            }
        });
    }

    public void dumpCache(IndentingPrintWriter pw) {
        mCurrentDefaultDialerPerUser.forEach((k, v) -> pw.printf("User %d: %s\n", k, v));
    }

    private void removeUserFromCache(int userId) {
        mCurrentDefaultDialerPerUser.remove(userId);
    }

    /**
     * registerContentObserver is really hard to mock out, so here is a getter method for the
     * content observer for testing instead.
     *
     * @return The content observer
     */
    @VisibleForTesting
    public ContentObserver getContentObserver() {
        return mDefaultDialerObserver;
    }

    public RoleManagerAdapter getRoleManagerAdapter() {
        return mRoleManagerAdapter;
    }

    public interface DefaultDialerManagerAdapter {
        String getDefaultDialerApplication(Context context);

        String getDefaultDialerApplication(Context context, int userId);

        boolean setDefaultDialerApplication(Context context, String packageName, int userId);
    }

    static class DefaultDialerManagerAdapterImpl implements DefaultDialerManagerAdapter {
        @Override
        public String getDefaultDialerApplication(Context context) {
            return DefaultDialerManager.getDefaultDialerApplication(context);
        }

        @Override
        public String getDefaultDialerApplication(Context context, int userId) {
            return DefaultDialerManager.getDefaultDialerApplication(context, userId);
        }

        @Override
        public boolean setDefaultDialerApplication(Context context, String packageName,
                int userId) {
            return DefaultDialerManager.setDefaultDialerApplication(context, packageName, userId);
        }
    }
}
