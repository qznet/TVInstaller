package com.bigsinger.tvinstaller.ui;

import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bigsinger.tvinstaller.R;
import com.bigsinger.tvinstaller.adapter.FileAdapter;
import com.bigsinger.tvinstaller.data.DeviceHistoryStore;
import com.bigsinger.tvinstaller.data.SmbEntry;
import com.bigsinger.tvinstaller.net.SmbRepository;
import com.bigsinger.tvinstaller.security.CredentialStore;
import com.bigsinger.tvinstaller.service.ApkDownloadService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FileListActivity extends AppCompatActivity {
    public static final String EXTRA_HOST = "extra_host";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";
    public static final String EXTRA_USERNAME = "extra_username";
    public static final String EXTRA_PASSWORD = "extra_password";
    public static final String EXTRA_INITIAL_PATH = "extra_initial_path";

    private final ArrayList<SmbEntry> entries = new ArrayList<SmbEntry>();
    private final SmbRepository repository = new SmbRepository();

    private String host;
    private String deviceName;
    private String username;
    private String password;
    private String currentPath = "";
    private int loadedCount = 0;

    private TextView fileTitle;
    private TextView pathText;
    private ProgressBar loadingBar;
    private Button loadMoreButton;
    private RecyclerView fileRecycler;
    private FileAdapter adapter;
    private ProgressDialog downloadDialog;
    private File pendingInstallFile;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(ApkDownloadService.EXTRA_STATUS);
            if (ApkDownloadService.STATUS_PROGRESS.equals(status)) {
                int progress = intent.getIntExtra(ApkDownloadService.EXTRA_PROGRESS, 0);
                if (downloadDialog != null) {
                    downloadDialog.setProgress(progress);
                    downloadDialog.setMessage("正在下载 APK..." + progress + "%");
                }
            } else if (ApkDownloadService.STATUS_COMPLETE.equals(status)) {
                dismissDownloadDialog();
                String path = intent.getStringExtra(ApkDownloadService.EXTRA_FILE_PATH);
                if (!TextUtils.isEmpty(path)) {
                    launchInstaller(new File(path));
                }
            } else if (ApkDownloadService.STATUS_ERROR.equals(status)) {
                dismissDownloadDialog();
                String error = intent.getStringExtra(ApkDownloadService.EXTRA_ERROR);
                Toast.makeText(FileListActivity.this, error, Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_list);

        host = getIntent().getStringExtra(EXTRA_HOST);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        username = getIntent().getStringExtra(EXTRA_USERNAME);
        password = getIntent().getStringExtra(EXTRA_PASSWORD);
        currentPath = normalizePath(getIntent().getStringExtra(EXTRA_INITIAL_PATH));
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username)) {
            Toast.makeText(this, "连接参数缺失", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fileTitle = findViewById(R.id.fileTitle);
        pathText = findViewById(R.id.pathText);
        loadingBar = findViewById(R.id.loadingBar);
        loadMoreButton = findViewById(R.id.loadMoreButton);
        Button backParentButton = findViewById(R.id.backParentButton);
        Button clearButton = findViewById(R.id.clearCredentialButton);

        fileRecycler = findViewById(R.id.fileRecycler);
        adapter = new FileAdapter(entries, new FileAdapter.Listener() {
            @Override
            public void onEntryClick(SmbEntry entry) {
                openEntry(entry);
            }
        });
        fileRecycler.setLayoutManager(new LinearLayoutManager(this));
        fileRecycler.setAdapter(adapter);

        backParentButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goParent();
            }
        });
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new CredentialStore(FileListActivity.this).clear(host);
                Toast.makeText(FileListActivity.this, "已清除当前设备凭证", Toast.LENGTH_SHORT).show();
            }
        });
        loadMoreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadEntries(false);
            }
        });

        updateTitle();
        loadEntries(true, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadReceiver,
                new IntentFilter(ApkDownloadService.ACTION_PROGRESS));
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallFile != null && canInstallPackages()) {
            File file = pendingInstallFile;
            pendingInstallFile = null;
            launchInstallerInternal(file);
        }
    }

    private void loadEntries(boolean reset) {
        loadEntries(reset, false);
    }

    private void loadEntries(boolean reset, boolean fallbackToRoot) {
        loadingBar.setVisibility(View.VISIBLE);
        loadMoreButton.setVisibility(View.GONE);
        if (reset) {
            loadedCount = 0;
            entries.clear();
            adapter.notifyDataSetChanged();
        }

        int offset = loadedCount;
        new AsyncTask<Void, Void, LoadResult>() {
            @Override
            protected LoadResult doInBackground(Void... voids) {
                try {
                    List<SmbEntry> page = repository.list(host, currentPath, username, password, offset);
                    boolean hasMore = repository.hasMore(host, currentPath, username, password, offset + page.size());
                    return LoadResult.success(page, hasMore);
                } catch (Exception e) {
                    return LoadResult.failure(e);
                }
            }

            @Override
            protected void onPostExecute(LoadResult result) {
                loadingBar.setVisibility(View.GONE);
                if (result.error != null) {
                    if (fallbackToRoot && !TextUtils.isEmpty(currentPath)) {
                        currentPath = "";
                        updateTitle();
                        Toast.makeText(FileListActivity.this, "上次目录不可用，已打开根目录", Toast.LENGTH_SHORT).show();
                        loadEntries(true, false);
                        return;
                    }
                    Toast.makeText(FileListActivity.this, readableListError(result.error), Toast.LENGTH_LONG).show();
                    return;
                }
                int start = entries.size();
                entries.addAll(result.entries);
                loadedCount = entries.size();
                adapter.notifyItemRangeInserted(start, result.entries.size());
                loadMoreButton.setVisibility(result.hasMore ? View.VISIBLE : View.GONE);
                new DeviceHistoryStore(FileListActivity.this).setLastPath(host, currentPath);
                if (reset) {
                    focusFileList();
                }
                if (entries.isEmpty()) {
                    Toast.makeText(FileListActivity.this, "当前目录没有 APK 或子文件夹", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    private String readableListError(Exception error) {
        String message = error.getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT).contains("timed")) {
            return "连接超时，请检查IP或防火墙设置";
        }
        return TextUtils.isEmpty(message) ? "文件列表加载失败" : message;
    }

    private void openEntry(SmbEntry entry) {
        if (entry.isDirectory()) {
            currentPath = SmbRepository.childPath(currentPath, entry.getName());
            updateTitle();
            loadEntries(true, false);
            return;
        }
        confirmInstall(entry);
    }

    private void goParent() {
        if (TextUtils.isEmpty(currentPath)) {
            finish();
            return;
        }
        currentPath = SmbRepository.parentPath(currentPath);
        updateTitle();
        loadEntries(true, false);
    }

    private void confirmInstall(SmbEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("安装应用")
                .setMessage("确定安装 " + entry.getName() + " 吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showDownloadDialog(entry.getName());
                        ApkDownloadService.start(FileListActivity.this,
                                entry.getUrl(),
                                username,
                                password,
                                entry.getName());
                    }
                })
                .show();
    }

    private void showDownloadDialog(String fileName) {
        downloadDialog = new ProgressDialog(this);
        downloadDialog.setTitle(fileName);
        downloadDialog.setMessage("正在下载 APK...");
        downloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        downloadDialog.setMax(100);
        downloadDialog.setProgress(0);
        downloadDialog.setCancelable(false);
        downloadDialog.show();
    }

    private void dismissDownloadDialog() {
        if (downloadDialog != null && downloadDialog.isShowing()) {
            downloadDialog.dismiss();
        }
        downloadDialog = null;
    }

    private void launchInstaller(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages()) {
            pendingInstallFile = apkFile;
            new AlertDialog.Builder(this)
                    .setTitle("允许安装未知应用")
                    .setMessage("系统禁止安装未知来源应用，请点击确认前往设置")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("前往设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        }
                    })
                    .show();
            return;
        }
        launchInstallerInternal(apkFile);
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls();
    }

    private void launchInstallerInternal(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(apkFile);
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "未找到系统安装器", Toast.LENGTH_LONG).show();
        }
    }

    private void updateTitle() {
        String name = TextUtils.isEmpty(deviceName) ? host : deviceName;
        fileTitle.setText(name);
        pathText.setText(SmbRepository.buildUrl(host, currentPath));
    }

    private void focusFileList() {
        fileRecycler.post(new Runnable() {
            @Override
            public void run() {
                fileRecycler.requestFocus();
                View child = fileRecycler.getChildAt(0);
                if (child != null) {
                    child.requestFocus();
                }
            }
        });
    }

    private String normalizePath(String path) {
        if (TextUtils.isEmpty(path)) {
            return "";
        }
        return path.endsWith("/") ? path : path + "/";
    }

    private static class LoadResult {
        final List<SmbEntry> entries;
        final boolean hasMore;
        final Exception error;

        private LoadResult(List<SmbEntry> entries, boolean hasMore, Exception error) {
            this.entries = entries;
            this.hasMore = hasMore;
            this.error = error;
        }

        static LoadResult success(List<SmbEntry> entries, boolean hasMore) {
            return new LoadResult(entries, hasMore, null);
        }

        static LoadResult failure(Exception error) {
            return new LoadResult(new ArrayList<SmbEntry>(), false, error);
        }
    }
}
