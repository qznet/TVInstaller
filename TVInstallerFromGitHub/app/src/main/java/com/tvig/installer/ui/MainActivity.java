package com.tvig.installer.ui;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvig.installer.R;
import com.tvig.installer.adapter.RepositoryAdapter;
import com.tvig.installer.data.RepositoryItem;
import com.tvig.installer.data.RepositoryStore;
import com.tvig.installer.net.SyncClient;
import com.tvig.installer.service.ApkDownloadService;

import java.io.File;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;

/** Main TV surface: repository list on the left and a real GitHub WebView on the right. */
public final class MainActivity extends AppCompatActivity implements RepositoryAdapter.Listener {
    private static final String PREVIEW_URL = "file:///android_asset/preview.html";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String STATE_PENDING_INSTALL = "pending_install";

    private final ArrayList<RepositoryItem> repositories = new ArrayList<>();

    private RepositoryStore repositoryStore;
    private SyncClient syncClient;
    private RepositoryAdapter repositoryAdapter;
    private RepositoryItem currentRepository;
    private RepositoryItem selectedRepository;

    private Button syncButton;
    private Button favoriteButton;
    private Button browserHomeButton;
    private Button browserBackButton;
    private Button browserRefreshButton;
    private TextView presetCountText;
    private TextView browserUrlText;
    private TextView downloadStatusText;
    private ProgressBar webProgress;
    private ProgressBar downloadProgress;
    private View downloadPanel;
    private View webContainer;
    private RecyclerView repoRecycler;
    private WebView webView;

    private boolean syncing;
    private boolean clearHistoryOnPreview;
    private boolean downloadFailed;
    private String mainFrameUrl;
    private String lastWebErrorUrl;
    private String lastDownloadUrl;
    private String lastDownloadFileName;
    private File pendingInstallFile;
    private Call syncCall;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (ApkDownloadService.ACTION_PROGRESS.equals(action)) {
                renderDownloadProgress(intent);
            } else if (ApkDownloadService.ACTION_COMPLETE.equals(action)) {
                renderDownloadComplete(intent);
            } else if (ApkDownloadService.ACTION_ERROR.equals(action)) {
                renderDownloadError(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repositoryStore = new RepositoryStore(this);
        syncClient = new SyncClient(repositoryStore);
        bindViews();
        styleTitle();
        configureRepositoryList();
        configureActions();
        configureWebView();
        reloadRepositories(null, null);

        if (savedInstanceState != null) {
            String path = savedInstanceState.getString(STATE_PENDING_INSTALL);
            if (!TextUtils.isEmpty(path)) {
                pendingInstallFile = new File(path);
            }
        }

        webView.loadUrl(PREVIEW_URL);
        syncButton.post(new Runnable() {
            @Override
            public void run() {
                syncButton.requestFocus();
            }
        });
    }

    private void bindViews() {
        syncButton = findViewById(R.id.syncButton);
        favoriteButton = findViewById(R.id.favoriteButton);
        browserHomeButton = findViewById(R.id.browserHomeButton);
        browserBackButton = findViewById(R.id.browserBackButton);
        browserRefreshButton = findViewById(R.id.browserRefreshButton);
        presetCountText = findViewById(R.id.presetCountText);
        browserUrlText = findViewById(R.id.browserUrlText);
        downloadStatusText = findViewById(R.id.downloadStatusText);
        webProgress = findViewById(R.id.webProgress);
        downloadProgress = findViewById(R.id.downloadProgress);
        downloadPanel = findViewById(R.id.downloadPanel);
        webContainer = findViewById(R.id.webContainer);
        repoRecycler = findViewById(R.id.repoRecycler);
        webView = findViewById(R.id.webView);
    }

    private void styleTitle() {
        TextView title = findViewById(R.id.titleText);
        String label = getString(R.string.header_title);
        SpannableString styled = new SpannableString(label);
        int accentStart = label.lastIndexOf("TV");
        if (accentStart >= 0) {
            styled.setSpan(new ForegroundColorSpan(
                            ContextCompat.getColor(this, R.color.focus)),
                    accentStart,
                    label.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(styled);

        Drawable icon = AppCompatResources.getDrawable(this, R.drawable.ic_launcher);
        if (icon != null) {
            int size = Math.round(28f * getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
            title.setCompoundDrawables(icon, null, null, null);
            title.setCompoundDrawablePadding(
                    Math.round(10f * getResources().getDisplayMetrics().density));
        }
    }

    private void configureRepositoryList() {
        repositoryAdapter = new RepositoryAdapter(repositories, this);
        repoRecycler.setLayoutManager(new LinearLayoutManager(this));
        repoRecycler.setAdapter(repositoryAdapter);
        repoRecycler.setHasFixedSize(false);
    }

    private void configureActions() {
        syncButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                synchronizePresets();
            }
        });
        favoriteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleCurrentFavorite();
            }
        });
        browserHomeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                returnToRepositoryList();
            }
        });
        browserBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                navigateBrowserBack();
            }
        });
        browserRefreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                webView.reload();
                webView.requestFocus();
            }
        });
        downloadPanel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (downloadFailed && !TextUtils.isEmpty(lastDownloadUrl)) {
                    startApkDownload(lastDownloadUrl, lastDownloadFileName);
                }
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBlockNetworkLoads(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                webProgress.setProgress(progress);
                webProgress.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new GitHubWebViewClient());
        webView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                webContainer.setSelected(hasFocus);
            }
        });
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                if (!isApkRequest(url, contentDisposition, mimeType)) {
                    Toast.makeText(MainActivity.this,
                            "仅支持直接下载 APK 文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                startApkDownload(normalizeGitHubApkUrl(url),
                        guessApkFileName(url, contentDisposition, mimeType));
            }
        });
    }

    private void synchronizePresets() {
        if (syncing) {
            return;
        }
        syncing = true;
        syncButton.setText(R.string.syncing);
        final String selectedRepository = currentRepository == null
                ? null : currentRepository.getRepository();
        final RepositoryItem.Source selectedSource = currentRepository == null
                ? null : currentRepository.getSource();
        syncCall = syncClient.sync(getString(R.string.preset_url), new SyncClient.Callback() {
            @Override
            public void onSuccess(List<RepositoryItem> syncedRepositories) {
                finishSync();
                replaceRepositories(syncedRepositories, selectedRepository, selectedSource);
                Toast.makeText(MainActivity.this,
                        R.string.sync_success, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Throwable error) {
                finishSync();
                if (isTimeout(error)) {
                    try {
                        List<RepositoryItem> bundled = repositoryStore.restoreBundledPreset();
                        replaceRepositories(bundled, selectedRepository, selectedSource);
                        Toast.makeText(MainActivity.this,
                                R.string.sync_timeout_fallback, Toast.LENGTH_LONG).show();
                        return;
                    } catch (Exception fallbackError) {
                        // Keep the last usable local configuration if packaged recovery also fails.
                    }
                }
                Toast.makeText(MainActivity.this,
                        R.string.sync_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishSync() {
        syncing = false;
        syncCall = null;
        syncButton.setText(R.string.sync);
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            if (current instanceof InterruptedIOException
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.US).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void reloadRepositories(String selectedRepository,
                                    RepositoryItem.Source selectedSource) {
        replaceRepositories(repositoryStore.getRepositories(), selectedRepository, selectedSource);
    }

    private void replaceRepositories(List<RepositoryItem> items,
                                     String selectedRepository,
                                     RepositoryItem.Source selectedSource) {
        repositories.clear();
        if (items != null) {
            repositories.addAll(items);
        }
        repositoryAdapter.setItems(repositories);

        int presetCount = 0;
        for (RepositoryItem item : repositories) {
            if (item.getSource() == RepositoryItem.Source.PRESET) {
                presetCount++;
            }
        }
        presetCountText.setText(getString(R.string.preset_count_format, presetCount));

        currentRepository = findRepository(selectedRepository, selectedSource);
        if (currentRepository == null && !repositories.isEmpty()) {
            currentRepository = repositories.get(0);
        }
        if (this.selectedRepository != null) {
            this.selectedRepository = findRepository(
                    this.selectedRepository.getRepository(),
                    this.selectedRepository.getSource());
        }
        repositoryAdapter.setSelectedItem(this.selectedRepository);
        updateFavoriteButton();
    }

    private RepositoryItem findRepository(String repository, RepositoryItem.Source source) {
        if (TextUtils.isEmpty(repository)) {
            return null;
        }
        RepositoryItem fallback = null;
        for (RepositoryItem item : repositories) {
            if (repository.equalsIgnoreCase(item.getRepository())) {
                if (item.getSource() == source) {
                    return item;
                }
                if (fallback == null) {
                    fallback = item;
                }
            }
        }
        return fallback;
    }

    private void toggleCurrentFavorite() {
        if (currentRepository == null) {
            Toast.makeText(this, "请先选择一个仓库", Toast.LENGTH_SHORT).show();
            return;
        }
        String repository = currentRepository.getRepository();
        RepositoryItem.Source source = currentRepository.getSource();
        selectedRepository = currentRepository;
        boolean nowFavorite;
        try {
            nowFavorite = repositoryStore.toggleFavorite(repository);
        } catch (RuntimeException error) {
            Toast.makeText(this, "收藏保存失败", Toast.LENGTH_SHORT).show();
            return;
        }
        reloadRepositories(repository, source);
        Toast.makeText(this,
                nowFavorite ? R.string.favorite_added : R.string.favorite_removed,
                Toast.LENGTH_SHORT).show();
        focusCurrentRepositoryIfListHadFocus();
    }

    private void updateFavoriteButton() {
        boolean available = currentRepository != null;
        favoriteButton.setEnabled(available);
        if (!available) {
            favoriteButton.setText(R.string.favorite);
            return;
        }
        favoriteButton.setText(repositoryStore.isFavorite(currentRepository.getRepository())
                ? R.string.remove_favorite : R.string.favorite);
    }

    @Override
    public void onRepositoryClick(@NonNull RepositoryItem item) {
        currentRepository = item;
        selectedRepository = item;
        repositoryAdapter.setSelectedItem(item);
        updateFavoriteButton();
        // The local preview may still be rendering when the user immediately enters a
        // repository after launch. Cancel it first so it cannot win the navigation race.
        final String repositoryUrl = item.getUrl();
        webView.stopLoading();
        browserUrlText.setText(displayUrl(repositoryUrl));
        webView.loadUrl(repositoryUrl);
        webView.requestFocus();
    }

    @Override
    public void onRepositoryFocus(@NonNull RepositoryItem item) {
        currentRepository = item;
        updateFavoriteButton();
    }

    private void returnToRepositoryList() {
        clearHistoryOnPreview = true;
        webView.loadUrl(PREVIEW_URL);
        focusCurrentRepository();
    }

    private void navigateBrowserBack() {
        if (webView.canGoBack()) {
            webView.goBack();
            webView.requestFocus();
        } else {
            returnToRepositoryList();
        }
    }

    private void focusCurrentRepositoryIfListHadFocus() {
        View focus = getCurrentFocus();
        if (focus != null && isDescendant(repoRecycler, focus)) {
            focusCurrentRepository();
        }
    }

    private void focusCurrentRepository() {
        if (currentRepository == null || repositories.isEmpty()) {
            syncButton.requestFocus();
            return;
        }
        int position = repositories.indexOf(currentRepository);
        if (position < 0) {
            position = 0;
        }
        final int target = position;
        repoRecycler.scrollToPosition(target);
        repoRecycler.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.ViewHolder holder =
                        repoRecycler.findViewHolderForAdapterPosition(target);
                if (holder != null) {
                    View card = holder.itemView.findViewById(R.id.repoCard);
                    if (card != null) {
                        card.requestFocus();
                        return;
                    }
                }
                repoRecycler.requestFocus();
            }
        });
    }

    private void startApkDownload(String url, String fileName) {
        Uri uri = TextUtils.isEmpty(url) ? null : Uri.parse(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            Toast.makeText(this, "APK 下载地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        String safeName = TextUtils.isEmpty(fileName)
                ? guessApkFileName(url, null, APK_MIME) : fileName;
        lastDownloadUrl = url;
        lastDownloadFileName = safeName;
        downloadFailed = false;
        downloadPanel.setVisibility(View.VISIBLE);
        downloadPanel.setFocusable(false);
        downloadPanel.setClickable(false);
        downloadProgress.setIndeterminate(true);
        downloadStatusText.setText(getString(R.string.download_start_format, safeName));
        Toast.makeText(this,
                getString(R.string.download_start_format, safeName),
                Toast.LENGTH_SHORT).show();
        try {
            ApkDownloadService.start(this, url, safeName);
        } catch (RuntimeException error) {
            showDownloadFailure("无法启动下载服务");
        }
    }

    private void renderDownloadProgress(Intent intent) {
        String url = intent.getStringExtra(ApkDownloadService.EXTRA_URL);
        String fileName = intent.getStringExtra(ApkDownloadService.EXTRA_FILE_NAME);
        if (!TextUtils.isEmpty(url)) {
            lastDownloadUrl = url;
        }
        if (!TextUtils.isEmpty(fileName)) {
            lastDownloadFileName = fileName;
        }
        int progress = intent.getIntExtra(ApkDownloadService.EXTRA_PROGRESS, -1);
        downloadFailed = false;
        downloadPanel.setVisibility(View.VISIBLE);
        downloadPanel.setFocusable(false);
        downloadPanel.setClickable(false);
        if (progress >= 0) {
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgress(progress);
            downloadStatusText.setText(getString(R.string.download_progress_format,
                    displayDownloadName(), progress));
        } else {
            downloadProgress.setIndeterminate(true);
            downloadStatusText.setText(
                    getString(R.string.download_start_format, displayDownloadName()));
        }
    }

    private void renderDownloadComplete(Intent intent) {
        String path = intent.getStringExtra(ApkDownloadService.EXTRA_FILE_PATH);
        if (TextUtils.isEmpty(path)) {
            showDownloadFailure(getString(R.string.download_failed));
            return;
        }
        File apk = new File(path);
        if (!apk.isFile() || apk.length() <= 0L) {
            showDownloadFailure(getString(R.string.download_failed));
            return;
        }
        downloadFailed = false;
        downloadProgress.setIndeterminate(false);
        downloadProgress.setProgress(100);
        downloadStatusText.setText(R.string.download_complete_install);
        promptInstall(apk);
    }

    private void renderDownloadError(Intent intent) {
        String url = intent.getStringExtra(ApkDownloadService.EXTRA_URL);
        if (!TextUtils.isEmpty(url)) {
            lastDownloadUrl = url;
        }
        String message = intent.getStringExtra(ApkDownloadService.EXTRA_ERROR);
        showDownloadFailure(TextUtils.isEmpty(message)
                ? getString(R.string.download_failed) : message);
    }

    private void showDownloadFailure(String message) {
        downloadFailed = true;
        downloadPanel.setVisibility(View.VISIBLE);
        downloadPanel.setFocusable(true);
        downloadPanel.setClickable(true);
        downloadProgress.setIndeterminate(false);
        downloadStatusText.setText(getString(R.string.download_failed_retry_format,
                message, getString(R.string.download_retry)));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String displayDownloadName() {
        return TextUtils.isEmpty(lastDownloadFileName) ? "APK" : lastDownloadFileName;
    }

    private void promptInstall(final File apkFile) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.download_complete_install)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.install, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestInstall(apkFile);
                    }
                })
                .show();
    }

    private void requestInstall(final File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstallFile = apkFile;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.unknown_sources_title)
                    .setMessage(R.string.unknown_sources_message)
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            pendingInstallFile = null;
                        }
                    })
                    .setPositiveButton(R.string.permission_open_settings,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Intent settingsIntent = new Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:" + getPackageName()));
                                    startActivity(settingsIntent);
                                }
                            })
                    .show();
            return;
        }
        launchInstaller(apkFile);
    }

    private void launchInstaller(File apkFile) {
        if (apkFile == null || !apkFile.isFile()) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apkFile);
            intent.setClipData(ClipData.newRawUri("APK", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(apkFile);
        }
        intent.setDataAndType(uri, APK_MIME);
        try {
            downloadStatusText.setText(R.string.installing);
            startActivity(intent);
            pendingInstallFile = null;
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "未找到系统安装器", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, "无法共享 APK 文件", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isApkRequest(String url, String contentDisposition, String mimeType) {
        if (APK_MIME.equalsIgnoreCase(mimeType)) {
            return true;
        }
        String guessed = URLUtil.guessFileName(
                url == null ? "download" : url,
                contentDisposition,
                mimeType);
        if (guessed.toLowerCase(Locale.US).endsWith(".apk")) {
            return true;
        }
        if (contentDisposition != null
                && contentDisposition.toLowerCase(Locale.US).contains(".apk")) {
            return true;
        }
        return url != null && Uri.decode(url).toLowerCase(Locale.US).contains(".apk");
    }

    private String guessApkFileName(String url, String contentDisposition, String mimeType) {
        String guessed = URLUtil.guessFileName(
                url == null ? "download.apk" : url,
                contentDisposition,
                mimeType);
        if (TextUtils.isEmpty(guessed)) {
            guessed = "download.apk";
        }
        if (!guessed.toLowerCase(Locale.US).endsWith(".apk")) {
            guessed += ".apk";
        }
        return guessed;
    }

    /** Converts a GitHub blob/raw page for an APK into its raw file URL without parsing DOM. */
    private String normalizeGitHubApkUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        Uri uri = Uri.parse(url);
        if (!"github.com".equalsIgnoreCase(uri.getHost())) {
            return url;
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 5
                || !("blob".equals(segments.get(2)) || "raw".equals(segments.get(2)))) {
            return url;
        }
        Uri.Builder raw = new Uri.Builder()
                .scheme("https")
                .authority("raw.githubusercontent.com")
                .appendPath(segments.get(0))
                .appendPath(segments.get(1))
                .appendPath(segments.get(3));
        for (int index = 4; index < segments.size(); index++) {
            raw.appendPath(segments.get(index));
        }
        return raw.build().toString();
    }

    private boolean handleWebNavigation(String url) {
        if (TextUtils.isEmpty(url)) {
            return true;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            if (isApkRequest(url, null, null)) {
                String normalized = normalizeGitHubApkUrl(url);
                startApkDownload(normalized, guessApkFileName(url, null, APK_MIME));
                return true;
            }
            return false;
        }
        if (PREVIEW_URL.equals(url)) {
            return false;
        }
        try {
            Intent external = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(external);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "无法打开此链接", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private String displayUrl(String url) {
        if (TextUtils.isEmpty(url) || PREVIEW_URL.equals(url)) {
            return getString(R.string.default_repository_url);
        }
        return url.replaceFirst("^https?://", "");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                toggleCurrentFavorite();
                return true;
            }
            View focus = getCurrentFocus();
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && focus != null
                    && isDescendant(repoRecycler, focus)
                    && currentRepository != null) {
                onRepositoryClick(currentRepository);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isDescendant(ViewGroup parent, View child) {
        View current = child;
        while (current != null) {
            if (current == parent) {
                return true;
            }
            if (!(current.getParent() instanceof View)) {
                return false;
            }
            current = (View) current.getParent();
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (webView != null && !PREVIEW_URL.equals(webView.getUrl())) {
            returnToRepositoryList();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ApkDownloadService.ACTION_PROGRESS);
        filter.addAction(ApkDownloadService.ACTION_COMPLETE);
        filter.addAction(ApkDownloadService.ACTION_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadReceiver, filter);
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallFile != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().canRequestPackageInstalls()) {
            File file = pendingInstallFile;
            pendingInstallFile = null;
            launchInstaller(file);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getCurrentFocus() == null && syncButton != null) {
            syncButton.post(new Runnable() {
                @Override
                public void run() {
                    syncButton.requestFocus();
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (pendingInstallFile != null) {
            outState.putString(STATE_PENDING_INSTALL, pendingInstallFile.getAbsolutePath());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (syncCall != null) {
            syncCall.cancel();
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setDownloadListener(null);
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class GitHubWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleWebNavigation(url);
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleWebNavigation(request == null || request.getUrl() == null
                    ? null : request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mainFrameUrl = url;
            lastWebErrorUrl = null;
            browserUrlText.setText(displayUrl(url));
            webProgress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            browserUrlText.setText(displayUrl(url));
            webProgress.setVisibility(View.GONE);
            if (clearHistoryOnPreview && PREVIEW_URL.equals(url)) {
                view.clearHistory();
                clearHistoryOnPreview = false;
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode,
                                    String description, String failingUrl) {
            if (TextUtils.equals(failingUrl, mainFrameUrl)
                    || TextUtils.equals(failingUrl, view.getUrl())) {
                showBrowserError(failingUrl);
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                showBrowserError(request.getUrl() == null
                        ? null : request.getUrl().toString());
            }
        }

        private void showBrowserError(String url) {
            if (TextUtils.equals(lastWebErrorUrl, url)) {
                return;
            }
            lastWebErrorUrl = url;
            Toast.makeText(MainActivity.this,
                    R.string.browser_error, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            Toast.makeText(MainActivity.this,
                    "GitHub 安全连接校验失败", Toast.LENGTH_LONG).show();
        }
    }
}
