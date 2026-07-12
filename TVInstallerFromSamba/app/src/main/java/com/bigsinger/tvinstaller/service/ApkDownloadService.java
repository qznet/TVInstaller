package com.bigsinger.tvinstaller.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.bigsinger.tvinstaller.net.SmbRepository;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

import jcifs.smb.SmbFile;

public class ApkDownloadService extends IntentService {
    public static final String ACTION_PROGRESS = "com.bigsinger.tvinstaller.DOWNLOAD_PROGRESS";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_USERNAME = "extra_username";
    public static final String EXTRA_PASSWORD = "extra_password";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_STATUS = "extra_status";
    public static final String EXTRA_PROGRESS = "extra_progress";
    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_ERROR = "extra_error";

    public static final String STATUS_PROGRESS = "progress";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_ERROR = "error";

    private static final String TAG = "ApkDownloadService";
    private static final int BUFFER_SIZE = 64 * 1024;

    public ApkDownloadService() {
        super("ApkDownloadService");
    }

    public static void start(Context context, String url, String username, String password, String fileName) {
        Intent intent = new Intent(context, ApkDownloadService.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_USERNAME, username);
        intent.putExtra(EXTRA_PASSWORD, password);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String url = intent.getStringExtra(EXTRA_URL);
        String username = intent.getStringExtra(EXTRA_USERNAME);
        String password = intent.getStringExtra(EXTRA_PASSWORD);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(fileName)) {
            sendError("下载参数不完整");
            return;
        }

        File downloadDir = new File(getDownloadRoot(), "download");
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            sendError("无法创建缓存目录");
            return;
        }

        File target = new File(downloadDir, sanitize(fileName));
        SmbRepository repository = new SmbRepository();
        try (SmbFile remote = repository.openFile(url, username, password);
             InputStream input = remote.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {

            long total = remote.length();
            long copied = 0L;
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            sendProgress(0);
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                copied += read;
                if (total > 0) {
                    sendProgress((int) Math.min(99, copied * 100 / total));
                }
            }
            output.flush();
            sendComplete(target);
        } catch (Exception e) {
            Log.e(TAG, "APK download failed", e);
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Failed to delete partial APK: " + target.getAbsolutePath());
            }
            sendError(readableError(e));
        }
    }

    private File getDownloadRoot() {
        File external = getExternalCacheDir();
        return external != null ? external : getCacheDir();
    }

    private String sanitize(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT).contains("space")) {
            return "存储空间不足，请清理缓存";
        }
        return TextUtils.isEmpty(message) ? "下载失败，请检查网络连接" : message;
    }

    private void sendProgress(int progress) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.putExtra(EXTRA_STATUS, STATUS_PROGRESS);
        intent.putExtra(EXTRA_PROGRESS, progress);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendComplete(File file) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.putExtra(EXTRA_STATUS, STATUS_COMPLETE);
        intent.putExtra(EXTRA_PROGRESS, 100);
        intent.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendError(String error) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.putExtra(EXTRA_STATUS, STATUS_ERROR);
        intent.putExtra(EXTRA_ERROR, error);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
