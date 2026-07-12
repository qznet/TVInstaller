package com.bigsinger.tvinstaller.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CredentialStore {
    private static final String TAG = "CredentialStore";
    private static final String PREFS_NAME = "smb_credentials";
    private static final String USER_SUFFIX = ".user";
    private static final String PASS_SUFFIX = ".pass";
    private static final int IV_LENGTH = 16;

    private final Context context;
    private final SharedPreferences preferences;

    public CredentialStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void save(String host, String username, String password) {
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username) || password == null) {
            return;
        }
        try {
            preferences.edit()
                    .putString(key(host, USER_SUFFIX), username)
                    .putString(key(host, PASS_SUFFIX), encrypt(password))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save SMB credential", e);
        }
    }

    public Credential get(String host) {
        String username = preferences.getString(key(host, USER_SUFFIX), null);
        String encrypted = preferences.getString(key(host, PASS_SUFFIX), null);
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(encrypted)) {
            return null;
        }
        try {
            String password = decrypt(encrypted);
            return new Credential(username, password);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decrypt SMB credential", e);
            clear(host);
            return null;
        }
    }

    public void clear(String host) {
        preferences.edit()
                .remove(key(host, USER_SUFFIX))
                .remove(key(host, PASS_SUFFIX))
                .apply();
    }

    public void clearAll() {
        preferences.edit().clear().apply();
    }

    private String key(String host, String suffix) {
        return host.replace('.', '_') + suffix;
    }

    private String encrypt(String plainText) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));

        byte[] payload = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        return Base64.encodeToString(payload, Base64.NO_WRAP);
    }

    private String decrypt(String encryptedText) throws Exception {
        byte[] payload = Base64.decode(encryptedText, Base64.NO_WRAP);
        if (payload.length <= IV_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted credential payload");
        }
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec(), new IvParameterSpec(iv));
        return new String(cipher.doFinal(encrypted), "UTF-8");
    }

    private SecretKeySpec keySpec() throws Exception {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (TextUtils.isEmpty(androidId)) {
            androidId = context.getPackageName();
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest((androidId + salt()).getBytes("UTF-8"));
        return new SecretKeySpec(key, "AES");
    }

    private String salt() {
        return "tv" + "_smb" + "_installer" + "_2026";
    }
}

