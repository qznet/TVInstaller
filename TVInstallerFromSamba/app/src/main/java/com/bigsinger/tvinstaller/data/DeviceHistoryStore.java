package com.bigsinger.tvinstaller.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DeviceHistoryStore {
    private static final String TAG = "DeviceHistoryStore";
    private static final String PREFS_NAME = "device_history";
    private static final String KEY_DEVICES = "devices";
    private static final String PATH_PREFIX = "path.";
    private static final int MAX_DEVICES = 20;

    private final SharedPreferences preferences;

    public DeviceHistoryStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<DeviceInfo> getDevices() {
        List<DeviceInfo> devices = new ArrayList<DeviceInfo>();
        String raw = preferences.getString(KEY_DEVICES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String name = object.optString("name");
                String address = object.optString("address");
                if (!TextUtils.isEmpty(address)) {
                    devices.add(new DeviceInfo(TextUtils.isEmpty(name) ? "上次使用设备" : name, address));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to read device history", e);
        }
        return devices;
    }

    public void saveDevice(DeviceInfo device) {
        if (device == null || TextUtils.isEmpty(device.getAddress())) {
            return;
        }
        List<DeviceInfo> devices = getDevices();
        List<DeviceInfo> updated = new ArrayList<DeviceInfo>();
        updated.add(device);
        for (DeviceInfo existing : devices) {
            if (!device.getAddress().equals(existing.getAddress()) && updated.size() < MAX_DEVICES) {
                updated.add(existing);
            }
        }
        saveDevices(updated);
    }

    public String getLastPath(String host) {
        if (TextUtils.isEmpty(host)) {
            return "";
        }
        return preferences.getString(PATH_PREFIX + host, "");
    }

    public void setLastPath(String host, String path) {
        if (TextUtils.isEmpty(host)) {
            return;
        }
        preferences.edit().putString(PATH_PREFIX + host, path == null ? "" : path).apply();
    }

    private void saveDevices(List<DeviceInfo> devices) {
        JSONArray array = new JSONArray();
        for (DeviceInfo device : devices) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", device.getName());
                object.put("address", device.getAddress());
                array.put(object);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to write device history item", e);
            }
        }
        preferences.edit().putString(KEY_DEVICES, array.toString()).apply();
    }
}
