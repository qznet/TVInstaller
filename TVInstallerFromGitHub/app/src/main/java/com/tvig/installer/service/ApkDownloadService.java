package com.tvig.installer.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Sequential APK downloader with HTTP Range resume support. */
public final class ApkDownloadService extends IntentService {
    public static final String ACTION_PROGRESS =
            "com.tvig.installer.action.DOWNLOAD_PROGRESS";
    public static final String ACTION_COMPLETE =
            "com.tvig.installer.action.DOWNLOAD_COMPLETE";
    public static final String ACTION_ERROR =
            "com.tvig.installer.action.DOWNLOAD_ERROR";

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_PROGRESS = "extra_progress";
    public static final String EXTRA_BYTES_DOWNLOADED = "extra_bytes_downloaded";
    public static final String EXTRA_TOTAL_BYTES = "extra_total_bytes";
    public static final String EXTRA_ERROR = "extra_error";

    private static final String TAG = "ApkDownloadService";
    private static final String DOWNLOAD_DIRECTORY = "apks";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long BROADCAST_INTERVAL_MS = 250L;
    private static final long BROADCAST_BYTE_INTERVAL = 1024L * 1024L;
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSATISFIED_RANGE = Pattern.compile(
            "bytes\\s+\\*/(\\d+)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private volatile Call activeCall;

    public ApkDownloadService() {
        super("ApkDownloadService");
    }

    public static void start(Context context, String url, String fileName) {
        Intent intent = new Intent(context, ApkDownloadService.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        String requestedName = intent.getStringExtra(EXTRA_FILE_NAME);
        HttpUrl parsedUrl = TextUtils.isEmpty(url) ? null : HttpUrl.parse(url);
        if (parsedUrl == null || !"https".equalsIgnoreCase(parsedUrl.scheme())) {
            sendError(url, "APK 下载地址无效或不是 HTTPS");
            return;
        }

        File directory = new File(getDownloadRoot(), DOWNLOAD_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            sendError(url, "无法创建 APK 缓存目录");
            return;
        }

        String fileName = sanitizeFileName(requestedName, parsedUrl);
        File completedFile = new File(directory, fileName);
        File partialFile = new File(directory, fileName + ".part");
        try {
            download(parsedUrl, partialFile, completedFile, fileName);
        } catch (Exception error) {
            Log.e(TAG, "APK download failed: " + parsedUrl, error);
            sendError(parsedUrl.toString(), readableError(error));
        } finally {
            activeCall = null;
        }
    }

    @Override
    public void onDestroy() {
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
        super.onDestroy();
    }

    private void download(HttpUrl url, File partialFile, File completedFile,
                          String fileName) throws IOException {
        long offset = partialFile.isFile() ? partialFile.length() : 0L;
        Response response = execute(url, offset);
        try {
            if (offset > 0L && response.code() == 416) {
                long remoteTotal = parseUnsatisfiedTotal(response.header("Content-Range"));
                if (remoteTotal == offset) {
                    finishDownload(url, partialFile, completedFile, fileName, offset);
                    return;
                }
                response.close();
                truncate(partialFile);
                offset = 0L;
                response = execute(url, 0L);
            } else if (offset > 0L && response.code() == 200) {
                // The server ignored Range. Restart rather than appending duplicate bytes.
                truncate(partialFile);
                offset = 0L;
            } else if (offset > 0L && response.code() == 206) {
                long rangeStart = parseRangeStart(response.header("Content-Range"));
                if (rangeStart != offset) {
                    response.close();
                    truncate(partialFile);
                    offset = 0L;
                    response = execute(url, 0L);
                }
            }

            if (!response.isSuccessful()) {
                throw new IOException("APK download failed with HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("APK response has no body");
            }

            long totalBytes = determineTotalBytes(response, offset, body.contentLength());
            long downloadedBytes = copyResponse(
                    body, partialFile, offset, totalBytes, url.toString(), fileName);
            if (totalBytes >= 0L && downloadedBytes != totalBytes) {
                throw new IOException(
                        "Incomplete APK: received " + downloadedBytes + " of " + totalBytes);
            }
            if (downloadedBytes <= 0L) {
                throw new IOException("Downloaded APK is empty");
            }
            finishDownload(url, partialFile, completedFile, fileName, downloadedBytes);
        } finally {
            response.close();
        }
    }

    private Response execute(HttpUrl url, long offset) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "TVInstallerFromGitHub/1.0");
        if (offset > 0L) {
            request.header("Range", "bytes=" + offset + "-");
        }
        Call call = client.newCall(request.build());
        activeCall = call;
        return call.execute();
    }

    private long copyResponse(ResponseBody body, File partialFile, long offset,
                              long totalBytes, String url, String fileName) throws IOException {
        RandomAccessFile output = new RandomAccessFile(partialFile, "rw");
        InputStream input = null;
        try {
            if (offset <= 0L) {
                output.setLength(0L);
                offset = 0L;
            }
            output.seek(offset);
            input = body.byteStream();
            long downloaded = offset;
            long lastBroadcastAt = 0L;
            long lastBroadcastBytes = -1L;
            sendProgress(url, fileName, downloaded, totalBytes);

            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("APK download interrupted");
                }
                output.write(buffer, 0, read);
                downloaded += read;
                long now = System.currentTimeMillis();
                if (now - lastBroadcastAt >= BROADCAST_INTERVAL_MS
                        || downloaded - lastBroadcastBytes >= BROADCAST_BYTE_INTERVAL) {
                    sendProgress(url, fileName, downloaded, totalBytes);
                    lastBroadcastAt = now;
                    lastBroadcastBytes = downloaded;
                }
            }
            output.getFD().sync();
            return downloaded;
        } finally {
            if (input != null) {
                input.close();
            }
            output.close();
        }
    }

    private void finishDownload(HttpUrl url, File partialFile, File completedFile,
                                String fileName, long totalBytes) throws IOException {
        if (!partialFile.isFile()) {
            throw new IOException("Partial APK file is missing");
        }
        if (completedFile.exists() && !completedFile.delete()) {
            throw new IOException("Unable to replace completed APK");
        }
        if (!partialFile.renameTo(completedFile)) {
            throw new IOException("Unable to finalize APK download");
        }

        Intent broadcast = baseBroadcast(ACTION_COMPLETE, url.toString(), fileName);
        broadcast.putExtra(EXTRA_FILE_PATH, completedFile.getAbsolutePath());
        broadcast.putExtra(EXTRA_PROGRESS, 100);
        broadcast.putExtra(EXTRA_BYTES_DOWNLOADED, totalBytes);
        broadcast.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgress(String url, String fileName,
                              long downloadedBytes, long totalBytes) {
        Intent broadcast = baseBroadcast(ACTION_PROGRESS, url, fileName);
        broadcast.putExtra(EXTRA_BYTES_DOWNLOADED, downloadedBytes);
        broadcast.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
        broadcast.putExtra(EXTRA_PROGRESS, calculateProgress(downloadedBytes, totalBytes));
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendError(String url, String message) {
        Intent broadcast = baseBroadcast(ACTION_ERROR, url, null);
        broadcast.putExtra(EXTRA_ERROR, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private Intent baseBroadcast(String action, String url, String fileName) {
        Intent intent = new Intent(action);
        if (url != null) {
            intent.putExtra(EXTRA_URL, url);
        }
        if (fileName != null) {
            intent.putExtra(EXTRA_FILE_NAME, fileName);
        }
        return intent;
    }

    private int calculateProgress(long downloadedBytes, long totalBytes) {
        if (totalBytes <= 0L) {
            return -1;
        }
        double percentage = downloadedBytes * 100.0d / totalBytes;
        return (int) Math.max(0, Math.min(99, Math.floor(percentage)));
    }

    private long determineTotalBytes(Response response, long offset, long bodyLength) {
        if (response.code() == 206) {
            long rangeTotal = parseRangeTotal(response.header("Content-Range"));
            if (rangeTotal >= 0L) {
                return rangeTotal;
            }
        }
        return bodyLength >= 0L ? offset + bodyLength : -1L;
    }

    private long parseRangeStart(String header) {
        Matcher matcher = header == null ? null : CONTENT_RANGE.matcher(header.trim());
        if (matcher == null || !matcher.matches()) {
            return -1L;
        }
        return parseLong(matcher.group(1));
    }

    private long parseRangeTotal(String header) {
        Matcher matcher = header == null ? null : CONTENT_RANGE.matcher(header.trim());
        if (matcher == null || !matcher.matches() || "*".equals(matcher.group(3))) {
            return -1L;
        }
        return parseLong(matcher.group(3));
    }

    private long parseUnsatisfiedTotal(String header) {
        Matcher matcher = header == null ? null : UNSATISFIED_RANGE.matcher(header.trim());
        if (matcher == null || !matcher.matches()) {
            return -1L;
        }
        return parseLong(matcher.group(1));
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private void truncate(File file) throws IOException {
        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
        try {
            randomAccessFile.setLength(0L);
        } finally {
            randomAccessFile.close();
        }
    }

    private File getDownloadRoot() {
        File external = getExternalCacheDir();
        return external != null ? external : getCacheDir();
    }

    private String sanitizeFileName(String requestedName, HttpUrl url) {
        String fileName = requestedName;
        if (TextUtils.isEmpty(fileName)) {
            List<String> segments = url.pathSegments();
            fileName = segments.isEmpty() ? "download.apk" : segments.get(segments.size() - 1);
        }
        fileName = fileName == null ? "" : fileName.trim();
        fileName = fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (fileName.toLowerCase(Locale.US).endsWith(".part")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        }
        if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName)) {
            fileName = "download";
        }
        if (!fileName.toLowerCase(Locale.US).endsWith(".apk")) {
            fileName += ".apk";
        }
        if (fileName.length() > 180) {
            fileName = fileName.substring(0, 176) + ".apk";
        }
        return fileName;
    }

    private String readableError(Exception error) {
        if (error instanceof InterruptedIOException) {
            return "APK 下载已中断，可稍后继续";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "APK 下载失败，请检查网络和存储空间";
        }
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("space") || lower.contains("enospc")) {
            return "存储空间不足，请清理缓存";
        }
        return message;
    }
}
