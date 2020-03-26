package com.mixpanel.android.mpmetrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.mixpanel.android.util.MPLog;

/**
 * Abstracts away possibly non-present system information classes,
 * and handles permission-dependent queries for default system information.
 */
/* package */ class SystemInformation {

    /* package */ static SystemInformation getInstance(Context context) {
        synchronized (sInstanceLock) {
            if (null == sInstance) {
                final Context appContext = context.getApplicationContext();
                sInstance = new SystemInformation(appContext);
            }
        }

        return sInstance;
    }

    private SystemInformation(Context context) {
        mContext = context;

        PackageManager packageManager = mContext.getPackageManager();

        String foundAppVersionName = null;
        Integer foundAppVersionCode = null;
        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(mContext.getPackageName(), 0);
            foundAppVersionName = packageInfo.versionName;
            foundAppVersionCode = packageInfo.versionCode;
        } catch (NameNotFoundException e) {
            MPLog.w(LOGTAG, "System information constructed with a context that apparently doesn't exist.");
        }

        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int appNameStringId = applicationInfo.labelRes;

        mAppVersionName = foundAppVersionName;
        mAppVersionCode = foundAppVersionCode;
        mAppName = appNameStringId == 0 ? applicationInfo.nonLocalizedLabel == null ? "Misc" : applicationInfo.nonLocalizedLabel.toString() : context.getString(appNameStringId);

        // We can't count on these features being available, since we need to
        // run on old devices. Thus, the reflection fandango below...
        Class<? extends PackageManager> packageManagerClass = packageManager.getClass();

        Method hasSystemFeatureMethod = null;
        try {
            hasSystemFeatureMethod = packageManagerClass.getMethod("hasSystemFeature", String.class);
        } catch (NoSuchMethodException e) {
            // Nothing, this is an expected outcome
        }

        Boolean foundNFC = null;
        Boolean foundTelephony = null;
        if (null != hasSystemFeatureMethod) {
            try {
                foundNFC = (Boolean) hasSystemFeatureMethod.invoke(packageManager, "android.hardware.nfc");
                foundTelephony = (Boolean) hasSystemFeatureMethod.invoke(packageManager, "android.hardware.telephony");
            } catch (InvocationTargetException e) {
                MPLog.w(LOGTAG, "System version appeared to support PackageManager.hasSystemFeature, but we were unable to call it.");
            } catch (IllegalAccessException e) {
                MPLog.w(LOGTAG, "System version appeared to support PackageManager.hasSystemFeature, but we were unable to call it.");
            }
        }

        mHasNFC = foundNFC;
        mHasTelephony = foundTelephony;
        mDisplayMetrics = new DisplayMetrics();

        Display display = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        display.getMetrics(mDisplayMetrics);
    }

    public String getAppVersionName() { return mAppVersionName; }

    public Integer getAppVersionCode() { return mAppVersionCode; }

    public String getAppName() { return mAppName; }

    public boolean hasNFC() { return mHasNFC; }

    public boolean hasTelephony() { return mHasTelephony; }

    public DisplayMetrics getDisplayMetrics() { return mDisplayMetrics; }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("MissingPermission")
    public String getPhoneRadioType(Context context) {
        final PackageManager packageManager = context.getPackageManager();
        final String packageName = context.getPackageName();

        if (packageManager == null || packageName == null) {
            return null;
        }

        if (PackageManager.PERMISSION_GRANTED != packageManager.checkPermission("android.permission.READ_PHONE_STATE", packageName)) {
            MPLog.w(LOGTAG, "Permission READ_PHONE_STATE not granted. Property $radio will not be available.");
            return null;
        }

        TelephonyManager telephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (null != telephonyManager) {
            int networkType;
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkType = telephonyManager.getDataNetworkType();
            } else {
                networkType = telephonyManager.getNetworkType();
            }
            switch (networkType) {
                case TelephonyManager.NETWORK_TYPE_1xRTT: return "1xRTT";
                case TelephonyManager.NETWORK_TYPE_CDMA: return "CDMA";
                case TelephonyManager.NETWORK_TYPE_EDGE: return "EDGE";
                case TelephonyManager.NETWORK_TYPE_EHRPD: return "eHRPD";
                case TelephonyManager.NETWORK_TYPE_EVDO_0: return "EVDO rev. 0";
                case TelephonyManager.NETWORK_TYPE_EVDO_A: return "EVDO rev. A";
                case TelephonyManager.NETWORK_TYPE_EVDO_B: return "EVDO rev. B";
                case TelephonyManager.NETWORK_TYPE_GPRS: return "GPRS";
                case TelephonyManager.NETWORK_TYPE_HSDPA: return "HSDPA";
                case TelephonyManager.NETWORK_TYPE_HSPA: return "HSPA";
                case TelephonyManager.NETWORK_TYPE_HSPAP: return "HSPA+";
                case TelephonyManager.NETWORK_TYPE_HSUPA: return "HSUPA";
                case TelephonyManager.NETWORK_TYPE_IDEN: return "iDen";
                case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
                case TelephonyManager.NETWORK_TYPE_UMTS: return "UMTS";
                case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "Unknown";
                default: return null;
            }
        }
        return null;
    }

    // Note this is the *current*, not the canonical network, because it
    // doesn't require special permissions to access. Unreliable for CDMA phones,
    //
    public String getCurrentNetworkOperator() {
        String ret = null;

        TelephonyManager telephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (null != telephonyManager) {
            ret = telephonyManager.getNetworkOperatorName();
        }

        return ret;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("MissingPermission")
    public Boolean isWifiConnected() {
        Boolean ret = null;

        if (PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
            ConnectivityManager connManager = (ConnectivityManager) this.mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connManager.getActiveNetworkInfo();
            ret = (networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected());
        }

        return ret;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("MissingPermission")
    public Boolean isBluetoothEnabled() {
        Boolean isBluetoothEnabled = null;
        try {
            PackageManager pm = mContext.getPackageManager();
            int hasBluetoothPermission = pm.checkPermission(
                    Manifest.permission.BLUETOOTH,
                    mContext.getPackageName());
            if (hasBluetoothPermission == PackageManager.PERMISSION_GRANTED) {
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter != null) {
                    isBluetoothEnabled = bluetoothAdapter.isEnabled();
                }
            }
        } catch (SecurityException e) {
            // do nothing since we don't have permissions
        } catch (NoClassDefFoundError e) {
            // Some phones doesn't have this class. Just ignore it
        }
        return isBluetoothEnabled;
    }

    public String getBluetoothVersion() {
        String bluetoothVersion = "none";
        if(android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 &&
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            bluetoothVersion = "ble";
        } else if(mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            bluetoothVersion = "classic";
        }
        return bluetoothVersion;
    }

    private final Context mContext;

    // Unchanging facts
    private final Boolean mHasNFC;
    private final Boolean mHasTelephony;
    private final DisplayMetrics mDisplayMetrics;
    private final String mAppVersionName;
    private final Integer mAppVersionCode;
    private final String mAppName;

    private static SystemInformation sInstance;
    private static final Object sInstanceLock = new Object();

    private static final String LOGTAG = "MixpanelAPI.SysInfo";
}
