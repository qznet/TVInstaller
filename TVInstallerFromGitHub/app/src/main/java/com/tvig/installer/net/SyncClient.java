package com.tvig.installer.net;

import android.os.Handler;
import android.os.Looper;

import com.tvig.installer.data.RepositoryItem;
import com.tvig.installer.data.RepositoryStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads a UTF-8 preset and commits it only after complete validation. */
public final class SyncClient {
    public interface Callback {
        void onSuccess(List<RepositoryItem> repositories);

        void onError(Throwable error);
    }

    private final RepositoryStore store;
    private final OkHttpClient client;
    private final Handler mainHandler;

    public SyncClient(RepositoryStore store) {
        this(store, new OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .build());
    }

    public SyncClient(RepositoryStore store, OkHttpClient client) {
        if (store == null || client == null) {
            throw new IllegalArgumentException("store and client must not be null");
        }
        this.store = store;
        this.client = client;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** Starts an asynchronous sync. Callback methods are delivered on the main thread. */
    public Call sync(String url, final Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url.trim());
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme())) {
            postError(callback, new IllegalArgumentException("Preset URL must be HTTPS"));
            return null;
        }

        Request request = new Request.Builder()
                .url(parsed)
                .header("Accept", "text/plain, text/*;q=0.9, */*;q=0.1")
                .header("Accept-Charset", "UTF-8")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "TVInstallerFromGitHub/1.0")
                .build();
        Call call = client.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                postError(callback, error);
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
                    postSuccess(callback, store.getRepositories());
                } catch (Exception error) {
                    postError(callback, error);
                } finally {
                    response.close();
                }
            }
        });
        return call;
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

    private void postSuccess(final Callback callback,
                             final List<RepositoryItem> repositories) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(repositories);
            }
        });
    }

    private void postError(final Callback callback, final Throwable error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(error);
            }
        });
    }
}
