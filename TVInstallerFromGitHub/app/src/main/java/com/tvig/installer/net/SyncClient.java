package com.tvig.installer.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tvig.installer.data.RepositoryItem;
import com.tvig.installer.data.RepositoryStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads a UTF-8 preset through automatic GitHub route failover. */
public final class SyncClient {
    private static final String TAG = "SyncClient";

    public interface Callback {
        void onSuccess(List<RepositoryItem> repositories);

        void onError(Throwable error);
    }

    public static final class SyncTask {
        private Call activeCall;
        private boolean canceled;

        public synchronized void cancel() {
            canceled = true;
            if (activeCall != null) {
                activeCall.cancel();
            }
        }

        synchronized boolean attach(Call call) {
            if (canceled) {
                call.cancel();
                return false;
            }
            activeCall = call;
            return true;
        }

        synchronized boolean isCanceled() {
            return canceled;
        }
    }

    private final RepositoryStore store;
    private final OkHttpClient client;
    private final GitHubRouteManager routeManager;
    private final Handler mainHandler;

    public SyncClient(Context context, RepositoryStore store) {
        this(store, new OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .build(), new GitHubRouteManager(context));
    }

    SyncClient(RepositoryStore store, OkHttpClient client,
               GitHubRouteManager routeManager) {
        if (store == null || client == null || routeManager == null) {
            throw new IllegalArgumentException("store, client and routeManager must not be null");
        }
        this.store = store;
        this.client = client;
        this.routeManager = routeManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** Starts an asynchronous sync. Callback methods are delivered on the main thread. */
    public SyncTask sync(String url, final Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url.trim());
        SyncTask task = new SyncTask();
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme())) {
            postError(task, callback, new IllegalArgumentException("Preset URL must be HTTPS"));
            return task;
        }

        List<String> candidates = routeManager.candidates(parsed.toString());
        attempt(task, candidates, 0, callback, null);
        return task;
    }

    private void attempt(final SyncTask task, final List<String> candidates,
                         final int index, final Callback callback,
                         final Throwable previousError) {
        if (task.isCanceled()) {
            return;
        }
        if (index >= candidates.size()) {
            postError(task, callback, previousError == null
                    ? new IOException("No preset route is available") : previousError);
            return;
        }

        final String candidateUrl = candidates.get(index);
        Request request = new Request.Builder()
                .url(candidateUrl)
                .header("Accept", "text/plain, text/*;q=0.9, */*;q=0.1")
                .header("Accept-Charset", "UTF-8")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "TVInstallerFromGitHub/1.0")
                .build();
        final Call call = client.newCall(request);
        if (!task.attach(call)) {
            return;
        }
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                failRoute(task, candidates, index, callback, candidateUrl, error);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        throw new IOException("Preset sync failed with HTTP " + response.code());
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("Preset response is empty");
                    }
                    long contentLength = body.contentLength();
                    if (contentLength > RepositoryStore.MAX_PRESET_BYTES) {
                        throw new IOException("Preset response is too large");
                    }
                    byte[] bytes = readLimited(body);
                    store.replacePresetAtomically(bytes);
                    routeManager.markSuccess(candidateUrl);
                    Log.i(TAG, "Preset route succeeded: "
                            + GitHubRouteManager.routeName(candidateUrl));
                    postSuccess(task, callback, store.getRepositories());
                } catch (Exception error) {
                    failRoute(task, candidates, index, callback, candidateUrl, error);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void failRoute(SyncTask task, List<String> candidates, int index,
                           Callback callback, String candidateUrl, Throwable error) {
        if (task.isCanceled()) {
            return;
        }
        routeManager.markFailure(candidateUrl);
        Log.w(TAG, "Preset route failed: "
                + GitHubRouteManager.routeName(candidateUrl), error);
        attempt(task, candidates, index + 1, callback, error);
    }

    private byte[] readLimited(ResponseBody body) throws IOException {
        InputStream input = body.byteStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > RepositoryStore.MAX_PRESET_BYTES) {
                    throw new IOException("Preset response is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private void postSuccess(final SyncTask task, final Callback callback,
                             final List<RepositoryItem> repositories) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!task.isCanceled()) {
                    callback.onSuccess(repositories);
                }
            }
        });
    }

    private void postError(final SyncTask task, final Callback callback,
                           final Throwable error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!task.isCanceled()) {
                    callback.onError(error);
                }
            }
        });
    }
}
