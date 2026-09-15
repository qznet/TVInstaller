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
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

public class SmbRepository {
    public static final int PAGE_SIZE = 100;

    /**
     * 尝试登录，自动按多种凭据策略回退（与 Windows 等客户端行为一致：
     * 先尝试用户输入的账号，失败再回退到匿名/Guest）。
     *
     * @return 登录成功时实际生效的凭据 {username, password}，供后续 list/open 复用；
     *         全部策略均失败则抛出最后一个异常。
     */
    public String[] authenticate(String host, String username, String password)
            throws IOException, CIFSException {
        List<NtlmPasswordAuthenticator> strategies = buildStrategies(username, password);
        Throwable lastError = null;
        for (NtlmPasswordAuthenticator auth : strategies) {
            CIFSContext ctx = baseContext().withCredentials(auth);
            SmbFile root = new SmbFile(rootUrl(host), ctx);
            try {
                root.connect();
                return effectiveCredentials(username, password, auth);
            } catch (SmbAuthException ae) {
                // 仅登录类失败才回退到下一策略；网络/超时等不再尝试，直接上抛
                lastError = ae;
            } catch (IOException e) {
                throw e;
            } finally {
                try {
                    root.close();
                } catch (Exception ignored) {
                    // no-op
                }
            }
        }
        if (lastError instanceof IOException) {
            throw (IOException) lastError;
        }
        throw new IOException("所有 SMB 登录方式均失败，请检查服务器是否开启 Guest/免密访问");
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

    /**
     * 凭据策略：
     * 1) 用户输入了用户名 -> 真实登录（保留用户名与密码，guest/guest、任意账号都走这里）；
     * 2) 匿名/Guest（空密码）-> 服务器免密或开启 guest 时由 jcifs-ng 自动回退到 guest 会话。
     */
    private List<NtlmPasswordAuthenticator> buildStrategies(String username, String password) {
        List<NtlmPasswordAuthenticator> list = new ArrayList<NtlmPasswordAuthenticator>();
        if (!TextUtils.isEmpty(username)) {
            list.add(new NtlmPasswordAuthenticator("", username, password));
        }
        list.add(new NtlmPasswordAuthenticator());
        return list;
    }

    private String[] effectiveCredentials(String username, String password, NtlmPasswordAuthenticator auth) {
        if (auth.isAnonymous()) {
            return new String[] { "", "" };
        }
        return new String[] {
                username == null ? "" : username,
                password == null ? "" : password
        };
    }

    private CIFSContext baseContext() throws CIFSException {
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.enableSMB2", "true");
        properties.setProperty("jcifs.smb.client.enableSMB1", "true");
        properties.setProperty("jcifs.smb.client.connTimeout", "5000");
        properties.setProperty("jcifs.smb.client.responseTimeout", "5000");
        properties.setProperty("jcifs.smb.client.soTimeout", "5000");

        return new BaseContext(new PropertyConfiguration(properties));
    }

    private CIFSContext context(String username, String password) throws CIFSException {
        return baseContext().withCredentials(resolveAuthenticator(username, password));
    }

    private NtlmPasswordAuthenticator resolveAuthenticator(String username, String password) {
        if (TextUtils.isEmpty(username)) {
            // 无参构造 = 匿名凭据（jcifs-ng 会按 guest 会话回退）
            return new NtlmPasswordAuthenticator();
        }
        return new NtlmPasswordAuthenticator("", username, password);
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
