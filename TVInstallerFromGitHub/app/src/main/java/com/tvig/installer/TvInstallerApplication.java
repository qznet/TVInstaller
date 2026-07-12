package com.tvig.installer;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/** Performs lightweight process-wide housekeeping. */
public final class TvInstallerApplication extends Application {
    private static final String TAG = "TvInstallerApplication";
    private static final String DOWNLOAD_DIRECTORY = "apks";

    @Override
    public void onCreate() {
        super.onCreate();
        cleanupCompletedApks(new File(getCacheDir(), DOWNLOAD_DIRECTORY));
        File externalCache = getExternalCacheDir();
        if (externalCache != null) {
            cleanupCompletedApks(new File(externalCache, DOWNLOAD_DIRECTORY));
        }
    }

    private void cleanupCompletedApks(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            // A resumable file ends in .apk.part, so this deliberately keeps every .part file.
            if (file.isFile()
                    && file.getName().toLowerCase(Locale.US).endsWith(".apk")
                    && !file.delete()) {
                Log.w(TAG, "Unable to delete old APK: " + file.getAbsolutePath());
            }
        }
    }
}
