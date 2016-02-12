/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.task;

import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Xml;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.view.IInputMethodManager;
import com.android.managedprovisioning.ProvisionLogger;
import com.android.managedprovisioning.R;
import com.android.managedprovisioning.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/**
 * Deletes all system apps with a launcher that are not in the required set of packages.
 * Furthermore deletes all disallowed apps.
 *
 * Note: If an app is mistakenly listed as both required and disallowed, it will be treated as
 * required.
 *
 * This task may be run when a profile (both for managed device and managed profile) is created.
 * In that case the newProfile flag should be true.
 *
 * It should also be run after a system update with newProfile false, if
 * {@link #shouldDeleteNonRequiredApps} returns true. Note that only newly installed system apps
 * will be deleted.
 */
public class DeleteNonRequiredAppsTask {
    private final Callback mCallback;
    private final Context mContext;
    private final String mMdmPackageName;
    private final IPackageManager mIPackageManager;
    private final IInputMethodManager mIInputMethodManager;
    private final PackageManager mPm;
    private final List<String> mRequiredAppsList;
    private final List<String> mDisallowedAppsList;
    private final List<String> mVendorRequiredAppsList;
    private final List<String> mVendorDisallowedAppsList;
    private final int mUserId;
    private final int mProvisioningType;
    private final boolean mNewProfile; // If we are provisioning a new managed profile/device.
    private final boolean mLeaveAllSystemAppsEnabled;

    private static final String TAG_SYSTEM_APPS = "system-apps";
    private static final String TAG_PACKAGE_LIST_ITEM = "item";
    private static final String ATTR_VALUE = "value";

    public static final int DEVICE_OWNER = 0;
    public static final int PROFILE_OWNER = 1;
    public static final int MANAGED_USER = 2;

    /**
     * Provisioning type should be either {@link #DEVICE_OWNER}, {@link #PROFILE_OWNER} or
     * {@link #MANAGED_USER}.
     **/
    public DeleteNonRequiredAppsTask(Context context, String mdmPackageName, int provisioningType,
            boolean newProfile, int userId, boolean leaveAllSystemAppsEnabled, Callback callback) {
        this(context, AppGlobals.getPackageManager(), getIInputMethodManager(), mdmPackageName,
                provisioningType, newProfile, userId, leaveAllSystemAppsEnabled, callback);
    }

    @VisibleForTesting
    DeleteNonRequiredAppsTask(Context context, IPackageManager iPm, IInputMethodManager iimm,
            String mdmPackageName, int provisioningType, boolean newProfile, int userId,
            boolean leaveAllSystemAppsEnabled, Callback callback) {

        mCallback = callback;
        mContext = context;
        mMdmPackageName = mdmPackageName;
        mProvisioningType = provisioningType;
        mUserId = userId;
        mNewProfile = newProfile;
        mLeaveAllSystemAppsEnabled = leaveAllSystemAppsEnabled;
        mPm = context.getPackageManager();
        mIPackageManager = iPm;
        mIInputMethodManager = iimm;

        int requiredAppsListArray;
        int vendorRequiredAppsListArray;
        int disallowedAppsListArray;
        int vendorDisallowedAppsListArray;
        if (mProvisioningType == DEVICE_OWNER) {
            requiredAppsListArray = R.array.required_apps_managed_device;
            disallowedAppsListArray = R.array.disallowed_apps_managed_device;
            vendorRequiredAppsListArray = R.array.vendor_required_apps_managed_device;
            vendorDisallowedAppsListArray = R.array.vendor_disallowed_apps_managed_device;
        } else if (mProvisioningType == PROFILE_OWNER) {
            requiredAppsListArray = R.array.required_apps_managed_profile;
            disallowedAppsListArray = R.array.disallowed_apps_managed_profile;
            vendorRequiredAppsListArray = R.array.vendor_required_apps_managed_profile;
            vendorDisallowedAppsListArray = R.array.vendor_disallowed_apps_managed_profile;
        } else if (mProvisioningType == MANAGED_USER) {
            requiredAppsListArray = R.array.required_apps_managed_user;
            disallowedAppsListArray = R.array.disallowed_apps_managed_user;
            vendorRequiredAppsListArray = R.array.vendor_required_apps_managed_user;
            vendorDisallowedAppsListArray = R.array.vendor_disallowed_apps_managed_user;
        } else {
            throw new IllegalArgumentException("Provisioning type " + mProvisioningType +
                    " not supported.");
        }

        Resources resources = mContext.getResources();
        mRequiredAppsList = Arrays.asList(resources.getStringArray(requiredAppsListArray));
        mDisallowedAppsList = Arrays.asList(resources.getStringArray(disallowedAppsListArray));
        mVendorRequiredAppsList = Arrays.asList(
                resources.getStringArray(vendorRequiredAppsListArray));
        mVendorDisallowedAppsList = Arrays.asList(
                resources.getStringArray(vendorDisallowedAppsListArray));
    }

    public void run() {
        if (mLeaveAllSystemAppsEnabled) {
            ProvisionLogger.logd("Not deleting non-required apps.");
            mCallback.onSuccess();
            return;
        }
        ProvisionLogger.logd("Deleting non required apps.");

        Set<String> packagesToDelete = getPackagesToDelete();
        removeNonInstalledPackages(packagesToDelete);

        if (packagesToDelete.isEmpty()) {
            mCallback.onSuccess();
            return;
        }

        PackageDeleteObserver packageDeleteObserver =
                new PackageDeleteObserver(packagesToDelete.size());
        for (String packageName : packagesToDelete) {
            ProvisionLogger.logd("Deleting package [" + packageName + "] as user " + mUserId);
            mPm.deletePackageAsUser(packageName, packageDeleteObserver,
                    PackageManager.DELETE_SYSTEM_APP, mUserId);
        }
    }

    private Set<String> getPackagesToDelete() {
        Set<String> packagesToDelete = getCurrentAppsWithLauncher();
        // Newly installed system apps are uninstalled when they are not required and are either
        // disallowed or have a launcher icon.
        packagesToDelete.removeAll(getRequiredApps());
        // Don't delete the system input method packages in case of Device owner provisioning.
        if (mProvisioningType == DEVICE_OWNER || mProvisioningType == MANAGED_USER) {
            packagesToDelete.removeAll(getSystemInputMethods());
        }
        packagesToDelete.addAll(getDisallowedApps());

        // Only consider new system apps.
        packagesToDelete.retainAll(getNewSystemApps());
        return packagesToDelete;
    }

    private Set<String> getNewSystemApps() {
        File systemAppsFile = getSystemAppsFile(mContext, mUserId);
        systemAppsFile.getParentFile().mkdirs(); // Creating the folder if it does not exist

        Set<String> currentSystemApps = Utils.getCurrentSystemApps(mIPackageManager, mUserId);
        final Set<String> previousSystemApps;
        if (mNewProfile) {
            // Provisioning case.
            previousSystemApps = Collections.<String>emptySet();
        } else  if (!systemAppsFile.exists()) {
            // OTA case.
            ProvisionLogger.loge("Could not find the system apps file " +
                    systemAppsFile.getAbsolutePath());
            mCallback.onError();
            return Collections.<String>emptySet();
        } else {
            previousSystemApps = readSystemApps(systemAppsFile);
        }

        writeSystemApps(currentSystemApps, systemAppsFile);
        Set<String> newApps = currentSystemApps;
        newApps.removeAll(previousSystemApps);
        return newApps;
    }

    /**
     * Remove all packages from the set that are not installed.
     */
    private void removeNonInstalledPackages(Set<String> packages) {
        Set<String> toBeRemoved = new HashSet<String>();
        for (String packageName : packages) {
            try {
                PackageInfo info = mPm.getPackageInfoAsUser(packageName, 0 /* default flags */,
                        mUserId);
                if (info == null) {
                    toBeRemoved.add(packageName);
                }
            } catch (PackageManager.NameNotFoundException e) {
                toBeRemoved.add(packageName);
            }
        }
        packages.removeAll(toBeRemoved);
    }

    /**
     * Returns if this task should be run on OTA.
     * This is indicated by the presence of the system apps file.
     */
    public static boolean shouldDeleteNonRequiredApps(Context context, int userId) {
        return getSystemAppsFile(context, userId).exists();
    }

    static File getSystemAppsFile(Context context, int userId) {
        return new File(context.getFilesDir() + File.separator + "system_apps"
                + File.separator + "user" + userId + ".xml");
    }

    private Set<String> getCurrentAppsWithLauncher() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = mPm.queryIntentActivitiesAsUser(launcherIntent,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE,
                mUserId);
        Set<String> apps = new HashSet<String>();
        for (ResolveInfo resolveInfo : resolveInfos) {
            apps.add(resolveInfo.activityInfo.packageName);
        }
        return apps;
    }

    private Set<String> getSystemInputMethods() {
        // InputMethodManager is final so it cannot be mocked.
        // So, we're using IInputMethodManager directly because it can be mocked.
        List<InputMethodInfo> inputMethods = null;
        try {
            inputMethods = mIInputMethodManager.getInputMethodList();
        } catch (RemoteException e) {
            ProvisionLogger.loge("Could not communicate with IInputMethodManager", e);
            return Collections.<String>emptySet();
        }
        Set<String> systemInputMethods = new HashSet<String>();
        for (InputMethodInfo inputMethodInfo : inputMethods) {
            ApplicationInfo applicationInfo = inputMethodInfo.getServiceInfo().applicationInfo;
            if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                systemInputMethods.add(inputMethodInfo.getPackageName());
            }
        }
        return systemInputMethods;
    }

    private void writeSystemApps(Set<String> packageNames, File systemAppsFile) {
        try {
            FileOutputStream stream = new FileOutputStream(systemAppsFile, false);
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(stream, "utf-8");
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_SYSTEM_APPS);
            for (String packageName : packageNames) {
                serializer.startTag(null, TAG_PACKAGE_LIST_ITEM);
                serializer.attribute(null, ATTR_VALUE, packageName);
                serializer.endTag(null, TAG_PACKAGE_LIST_ITEM);
            }
            serializer.endTag(null, TAG_SYSTEM_APPS);
            serializer.endDocument();
            stream.close();
        } catch (IOException e) {
            ProvisionLogger.loge("IOException trying to write the system apps", e);
        }
    }

    private Set<String> readSystemApps(File systemAppsFile) {
        Set<String> result = new HashSet<String>();
        if (!systemAppsFile.exists()) {
            return result;
        }
        try {
            FileInputStream stream = new FileInputStream(systemAppsFile);

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type = parser.next();
            int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                String tag = parser.getName();
                if (tag.equals(TAG_PACKAGE_LIST_ITEM)) {
                    result.add(parser.getAttributeValue(null, ATTR_VALUE));
                } else {
                    ProvisionLogger.loge("Unknown tag: " + tag);
                }
            }
            stream.close();
        } catch (IOException e) {
            ProvisionLogger.loge("IOException trying to read the system apps", e);
        } catch (XmlPullParserException e) {
            ProvisionLogger.loge("XmlPullParserException trying to read the system apps", e);
        }
        return result;
    }

    protected Set<String> getRequiredApps() {
        HashSet<String> requiredApps = new HashSet<String>();
        requiredApps.addAll(mRequiredAppsList);
        requiredApps.addAll(mVendorRequiredAppsList);
        requiredApps.add(mMdmPackageName);
        return requiredApps;
    }

    private Set<String> getDisallowedApps() {
        HashSet<String> disallowedApps = new HashSet<String>();
        disallowedApps.addAll(mDisallowedAppsList);
        disallowedApps.addAll(mVendorDisallowedAppsList);
        return disallowedApps;
    }

    /**
     * Runs the next task when all packages have been deleted or shuts down the activity if package
     * deletion fails.
     */
    class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        private final AtomicInteger mPackageCount = new AtomicInteger(0);

        public PackageDeleteObserver(int packageCount) {
            this.mPackageCount.set(packageCount);
        }

        @Override
        public void packageDeleted(String packageName, int returnCode) {
            if (returnCode != PackageManager.DELETE_SUCCEEDED) {
                ProvisionLogger.logw(
                        "Could not finish the provisioning: package deletion failed");
                mCallback.onError();
                return;
            }
            int currentPackageCount = mPackageCount.decrementAndGet();
            if (currentPackageCount == 0) {
                ProvisionLogger.logi("All non-required system apps with launcher icon, "
                        + "and all disallowed apps have been uninstalled.");
                mCallback.onSuccess();
            }
        }
    }

    private static IInputMethodManager getIInputMethodManager() {
        IBinder b = ServiceManager.getService(Context.INPUT_METHOD_SERVICE);
        return IInputMethodManager.Stub.asInterface(b);
    }

    public abstract static class Callback {
        public abstract void onSuccess();
        public abstract void onError();
    }
}
