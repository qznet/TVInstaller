package com.bigsinger.tvinstaller.net;

import android.text.TextUtils;

import com.bigsinger.tvinstaller.data.SmbEntry;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public class SmbRepository {
    public static final int PAGE_SIZE = 100;

    public void authenticate(String host, String username, String password) throws IOException, CIFSException {
        SmbFile root = new SmbFile(rootUrl(host), context(username, password));
        try {
            root.connect();
        } finally {
            root.close();
        }
    }

    public List<SmbEntry> list(String host, String path, String username, String password, int offset)
            throws IOException, CIFSException {
        String url = buildUrl(host, path);
        SmbFile root = new SmbFile(url, context(username, password));
        try {
            SmbFile[] files = root.listFiles();
            List<SmbEntry> entries = new ArrayList<SmbEntry>();
            if (files != null) {
                for (SmbFile file : files) {
                    SmbEntry entry = toEntry(file);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            sort(entries);
            int end = Math.min(entries.size(), offset + PAGE_SIZE);
            if (offset >= entries.size()) {
                return Collections.emptyList();
            }
            return new ArrayList<SmbEntry>(entries.subList(offset, end));
        } finally {
            root.close();
        }
    }

    public boolean hasMore(String host, String path, String username, String password, int loadedCount)
            throws IOException, CIFSException {
        String url = buildUrl(host, path);
        SmbFile root = new SmbFile(url, context(username, password));
        try {
            SmbFile[] files = root.listFiles();
            int count = 0;
            if (files != null) {
                for (SmbFile file : files) {
                    if (toEntry(file) != null) {
                        count++;
                    }
                }
            }
            return count > loadedCount;
        } finally {
            root.close();
        }
    }

    public SmbFile openFile(String url, String username, String password) throws MalformedURLException, CIFSException {
        return new SmbFile(url, context(username, password));
    }

    public static String rootUrl(String host) {
        return "smb://" + host + "/";
    }

    public static String childPath(String currentPath, String childName) {
        String cleanChild = childName == null ? "" : childName;
        if (cleanChild.endsWith("/")) {
            cleanChild = cleanChild.substring(0, cleanChild.length() - 1);
        }
        if (TextUtils.isEmpty(currentPath)) {
            return cleanChild + "/";
        }
        return currentPath + cleanChild + "/";
    }

    public static String parentPath(String currentPath) {
        if (TextUtils.isEmpty(currentPath)) {
            return "";
        }
        String path = currentPath.endsWith("/")
                ? currentPath.substring(0, currentPath.length() - 1)
                : currentPath;
        int index = path.lastIndexOf('/');
        if (index < 0) {
            return "";
        }
        return path.substring(0, index + 1);
    }

    public static String buildUrl(String host, String path) {
        if (TextUtils.isEmpty(path)) {
            return rootUrl(host);
        }
        return rootUrl(host) + path;
    }

    private CIFSContext context(String username, String password) throws CIFSException {
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.enableSMB2", "true");
        properties.setProperty("jcifs.smb.client.connTimeout", "5000");
        properties.setProperty("jcifs.smb.client.responseTimeout", "5000");
        properties.setProperty("jcifs.smb.client.soTimeout", "5000");

        CIFSContext base = new BaseContext(new PropertyConfiguration(properties));
        NtlmPasswordAuthenticator authenticator;
        if (TextUtils.isEmpty(username) || "guest".equalsIgnoreCase(username.trim())) {
            // 匿名/Guest 登录：服务器开启免密或 guest 账号时以此方式接入。
            // 必须传 null 用户名让 jcifs-ng 置 guest 标志，不能用带用户名的真实登录（否则密码不符被拒）。
            authenticator = new NtlmPasswordAuthenticator(null, null, null);
        } else {
            authenticator = new NtlmPasswordAuthenticator("", username, password);
        }
        return base.withCredentials(authenticator);
    }

    private SmbEntry toEntry(SmbFile file) throws IOException {
        String name = file.getName();
        if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) {
            return null;
        }

        boolean directory = file.isDirectory();
        if (!directory && !name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            return null;
        }

        long size = directory ? 0L : safeLength(file);
        long modified = safeLastModified(file);
        return new SmbEntry(name, file.getURL().toString(), directory, size, modified);
    }

    private long safeLength(SmbFile file) {
        try {
            return file.length();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long safeLastModified(SmbFile file) {
        try {
            return file.lastModified();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void sort(List<SmbEntry> entries) {
        Collections.sort(entries, new Comparator<SmbEntry>() {
            @Override
            public int compare(SmbEntry left, SmbEntry right) {
                if (left.isDirectory() != right.isDirectory()) {
                    return left.isDirectory() ? -1 : 1;
                }
                if (left.isDirectory()) {
                    return left.getName().compareToIgnoreCase(right.getName());
                }
                long diff = right.getModified() - left.getModified();
                if (diff > 0) {
                    return 1;
                }
                if (diff < 0) {
                    return -1;
                }
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
    }
}
