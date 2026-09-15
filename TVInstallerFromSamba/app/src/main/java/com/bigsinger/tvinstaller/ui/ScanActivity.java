package com.bigsinger.tvinstaller.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.bigsinger.tvinstaller.R;
import com.bigsinger.tvinstaller.data.DeviceHistoryStore;
import com.bigsinger.tvinstaller.data.DeviceInfo;
import com.bigsinger.tvinstaller.net.LanScanner;
import com.bigsinger.tvinstaller.net.SmbRepository;
import com.bigsinger.tvinstaller.security.Credential;
import com.bigsinger.tvinstaller.security.CredentialStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ScanActivity extends Activity {
    private static final int REQ_STORAGE = 100;

    private final LanScanner scanner = new LanScanner();
    private final ArrayList<DeviceInfo> devices = new ArrayList<DeviceInfo>();

    private CredentialStore credentialStore;
    private DeviceHistoryStore historyStore;
    private LinearLayout deviceList;
    private ScrollView deviceScroll;
    private TextView statusBadge;
    private TextView scanMessage;
    private TextView scanCount;
    private TextView emptyText;
    private ProgressBar scanProgress;
    private int scannedFoundCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        credentialStore = new CredentialStore(this);
        historyStore = new DeviceHistoryStore(this);
        statusBadge = findViewById(R.id.statusBadge);
        scanMessage = findViewById(R.id.scanMessage);
        scanCount = findViewById(R.id.scanCount);
        scanProgress = findViewById(R.id.scanProgress);
        emptyText = findViewById(R.id.emptyText);
        deviceScroll = findViewById(R.id.deviceScroll);
        deviceList = findViewById(R.id.deviceList);

        Button manualIpButton = findViewById(R.id.manualIpButton);
        Button refreshButton = findViewById(R.id.refreshButton);
        manualIpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualIpDialog();
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });

        requestStoragePermissionIfNeeded();
        loadCachedDevices();
        startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanner.cancel();
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    private void loadCachedDevices() {
        devices.clear();
        devices.addAll(historyStore.getDevices());
        renderDevices();
        focusDeviceListIfPossible();
    }

    private void startScan() {
        loadCachedDevices();
        scannedFoundCount = 0;
        statusBadge.setText("扫描中");
        scanMessage.setText(R.string.scan_running);
        scanCount.setText(String.format(Locale.getDefault(), "已发现 %d 个设备", devices.size()));
        scanProgress.setProgress(0);

        scanner.scan(this, new LanScanner.Listener() {
            @Override
            public void onScanStarted(int totalHosts) {
                scanProgress.setMax(Math.max(totalHosts, 1));
            }

            @Override
            public void onProgress(int completedHosts, int totalHosts, int foundCount) {
                scanProgress.setProgress(completedHosts);
                scanCount.setText(String.format(Locale.getDefault(), "已发现 %d 个设备", devices.size()));
            }

            @Override
            public void onDeviceFound(DeviceInfo device) {
                scannedFoundCount++;
                addDeviceIfMissing(device);
            }

            @Override
            public void onScanCompleted(List<DeviceInfo> foundDevices) {
                statusBadge.setText("扫描完成");
                if (devices.isEmpty()) {
                    scanMessage.setText(R.string.scan_empty);
                } else {
                    scanMessage.setText(String.format(Locale.getDefault(),
                            "列表中有 %d 个设备，可直接选择连接", devices.size()));
                }
                scanCount.setText(String.format(Locale.getDefault(), "本次扫描发现 %d 个设备", scannedFoundCount));
            }

            @Override
            public void onScanFailed(String message) {
                statusBadge.setText("扫描失败");
                scanMessage.setText(message);
            }
        });
    }

    private void renderDevices() {
        deviceList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final DeviceInfo device : devices) {
            View item = inflater.inflate(R.layout.item_device, deviceList, false);
            TextView nameText = item.findViewById(R.id.nameText);
            TextView addressText = item.findViewById(R.id.addressText);
            nameText.setText(device.getName());
            addressText.setText(device.getAddress() + " · 点击连接");
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    connect(device);
                }
            });
            deviceList.addView(item);
        }
        updateEmptyState();
    }

    private void addDeviceIfMissing(DeviceInfo device) {
        if (device == null || TextUtils.isEmpty(device.getAddress())) {
            return;
        }
        for (DeviceInfo existing : devices) {
            if (device.getAddress().equals(existing.getAddress())) {
                return;
            }
        }
        devices.add(device);
        renderDevices();
        focusDeviceListIfPossible();
    }

    private void updateEmptyState() {
        emptyText.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void focusDeviceListIfPossible() {
        if (devices.isEmpty() || deviceScroll.hasFocus()) {
            return;
        }
        View currentFocus = getCurrentFocus();
        if (currentFocus != null && currentFocus != emptyText) {
            return;
        }
        deviceScroll.post(new Runnable() {
            @Override
            public void run() {
                deviceScroll.requestFocus();
                if (deviceList.getChildCount() > 0) {
                    deviceList.getChildAt(0).requestFocus();
                }
            }
        });
    }

    private void connect(DeviceInfo device) {
        Credential saved = credentialStore.get(device.getAddress());
        if (saved != null) {
            authenticate(device, saved.getUsername(), saved.getPassword(), true);
            return;
        }
        showLoginDialog(device, null);
    }

    private void showLoginDialog(DeviceInfo device, Credential defaultCredential) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_login, null, false);
        EditText userEdit = view.findViewById(R.id.userEdit);
        EditText passwordEdit = view.findViewById(R.id.passwordEdit);
        CheckBox rememberCheck = view.findViewById(R.id.rememberCheck);
        if (defaultCredential != null) {
            userEdit.setText(defaultCredential.getUsername());
            passwordEdit.setText(defaultCredential.getPassword());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("连接 " + device.getAddress())
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("登录", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String username = userEdit.getText().toString().trim();
                        String password = passwordEdit.getText().toString();
                        // 用户名和密码留空 = 匿名(Guest)登录；填了用户名则用真实账号登录。
                        // 不再强制要求非空，否则免密/Guest 服务器永远连不上。
                        boolean remember = rememberCheck.isChecked() && !TextUtils.isEmpty(username);
                        if (remember) {
                            credentialStore.save(device.getAddress(), username, password);
                        }
                        dialog.dismiss();
                        authenticate(device, username, password, remember);
                    }
                });
            }
        });
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void authenticate(DeviceInfo device, String username, String password, boolean remember) {
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("正在连接 " + device.getAddress());
        progressDialog.setCancelable(false);
        progressDialog.show();

        new AsyncTask<Void, Void, Exception>() {
            @Override
            protected Exception doInBackground(Void... voids) {
                try {
                    new SmbRepository().authenticate(device.getAddress(), username, password);
                    return null;
                } catch (Exception e) {
                    return e;
                }
            }

            @Override
            protected void onPostExecute(Exception error) {
                progressDialog.dismiss();
                if (error == null) {
                    if (remember) {
                        credentialStore.save(device.getAddress(), username, password);
                    }
                    historyStore.saveDevice(device);
                    openFileList(device, username, password);
                    return;
                }

                Toast.makeText(ScanActivity.this, readableAuthError(error), Toast.LENGTH_LONG).show();
                showLoginDialog(device, new Credential(username, password));
            }
        }.execute();
    }

    private String readableAuthError(Exception error) {
        String message = error.getMessage();
        if (message != null && message.contains("C000006D")) {
            return "登录失败：用户名或密码错误 (0xC000006D)。若服务器为免密/Guest，请留空用户名和密码再登录";
        }
        if (message != null && message.toLowerCase(Locale.ROOT).contains("timed")) {
            return "连接超时，请检查IP或防火墙设置";
        }
        return TextUtils.isEmpty(message) ? "连接失败，请检查用户名、密码或网络" : message;
    }

    private void openFileList(DeviceInfo device, String username, String password) {
        Intent intent = new Intent(this, FileListActivity.class);
        intent.putExtra(FileListActivity.EXTRA_HOST, device.getAddress());
        intent.putExtra(FileListActivity.EXTRA_DEVICE_NAME, device.getName());
        intent.putExtra(FileListActivity.EXTRA_USERNAME, username);
        intent.putExtra(FileListActivity.EXTRA_PASSWORD, password);
        intent.putExtra(FileListActivity.EXTRA_INITIAL_PATH, historyStore.getLastPath(device.getAddress()));
        startActivity(intent);
    }

    private void showManualIpDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("例如 192.168.1.100");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手动添加 IP")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("连接", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String address = input.getText().toString().trim();
                        if (!isValidIp(address)) {
                            Toast.makeText(ScanActivity.this, "请输入有效 IP 地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        DeviceInfo device = new DeviceInfo("手动添加设备", address);
                        addDeviceIfMissing(device);
                        dialog.dismiss();
                        connect(device);
                    }
                });
            }
        });
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void styleDialogButtons(AlertDialog dialog) {
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (positive != null) {
            positive.setBackgroundResource(R.drawable.bg_button_primary);
            positive.setTextColor(getResources().getColor(R.color.text_primary));
        }
        if (negative != null) {
            negative.setBackgroundResource(R.drawable.bg_button_secondary);
            negative.setTextColor(getResources().getColor(R.color.text_primary));
        }
    }

    private boolean isValidIp(String address) {
        if (TextUtils.isEmpty(address)) {
            return false;
        }
        String[] parts = address.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
