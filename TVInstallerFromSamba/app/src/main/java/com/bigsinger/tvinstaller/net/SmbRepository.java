package com.bigsinger.tvinstaller.net;

import android.text.TextUtils;

import com.bigsinger.tvinstaller.data.SmbEntry;

import java.io.IOException;
import java.lang.reflect.Method;
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
     * 尝试登录，按多种凭据策略依次回退（覆盖 Samba `map to guest` 的各类配置）：
     *   1) 用户输入了用户名 -> 真实账号登录（保留用户名/密码，guest/guest、任意账号都走这里）；
     *   2) 显式 guest 会话：guest/guest（Samba 常见 guest 账号）；
     *   3) 匿名（无参构造 = 匿名 guest 会话回退）。
     * 任一策略成功即返回实际生效凭据；全部失败则抛出携带每条策略诊断的异常。
     */
    public String[] authenticate(String host, String username, String password)
            throws IOException, CIFSException {
        List<NtlmPasswordAuthenticator> strategies = buildStrategies(username, password);
        StringBuilder diag = new StringBuilder();
        Throwable lastError = null;
        for (NtlmPasswordAuthenticator auth : strategies) {
            String label = strategyLabel(username, password, auth);
            CIFSContext ctx = baseContext().withCredentials(auth);
            SmbFile root = new SmbFile(rootUrl(host), ctx);
            try {
                root.connect();
                diag.append("✓ 成功: ").append(label).append("\n");
                return effectiveCredentials(username, password, auth);
            } catch (SmbAuthException ae) {
                // 仅登录类失败才回退到下一策略；网络/超时等不再尝试，直接上抛
                lastError = ae;
                diag.append("✗ ").append(label).append(" -> ").append(ae.getClass().getSimpleName())
                        .append(" ").append(ntStatusOf(ae)).append(" ").append(safeMsg(ae)).append("\n");
            } catch (IOException e) {
                lastError = e;
                diag.append("✗ ").append(label).append(" -> IO ").append(e.getClass().getSimpleName())
                        .append(" ").append(safeMsg(e)).append("\n");
                throw new IOException(buildDiag("连接/网络错误，已停止尝试其它凭据", diag), e);
            } finally {
                try {
                    root.close();
                } catch (Exception ignored) {
                    // no-op
                }
            }
        }
        throw new IOException(buildDiag("所有 SMB 登录方式均失败，请检查服务器是否开启 Guest/免密访问", diag),
                lastError instanceof IOException ? (IOException) lastError : new IOException(diag.toString()));
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
     * 凭据策略（顺序即尝试顺序）：
     *   1) 用户输入了用户名 -> 真实账号登录（保留密码）；
     *   2) 显式 guest 会话 guest/guest（覆盖 Samba 常见配置）；
     *   3) 匿名（无参构造，jcifs-ng 按 guest 会话回退）。
     */
    private List<NtlmPasswordAuthenticator> buildStrategies(String username, String password) {
        List<NtlmPasswordAuthenticator> list = new ArrayList<NtlmPasswordAuthenticator>();
        if (!TextUtils.isEmpty(username)) {
            list.add(new NtlmPasswordAuthenticator("", username, password));
        }
        list.add(new NtlmPasswordAuthenticator("", "guest", "guest"));
        list.add(new NtlmPasswordAuthenticator());
        return list;
    }

    private String strategyLabel(String username, String password, NtlmPasswordAuthenticator auth) {
        if (auth.isAnonymous()) {
            return "匿名(无凭据)";
        }
        String u = username == null ? "" : username;
        if ("guest".equalsIgnoreCase(u.trim())) {
            return "Guest账号(guest/" + (password == null ? "" : password) + ")";
        }
        return "账号登录(" + u + "/" + (password == null ? "" : password) + ")";
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

    private static String ntStatusOf(Throwable t) {
        try {
            Method m = t.getClass().getMethod("getNtStatus");
            Object v = m.invoke(t);
            if (v instanceof Integer) {
                int code = (Integer) v;
                if (code != 0) {
                    return "NTSTATUS=0x" + Integer.toHexString(code).toUpperCase();
                }
            }
        } catch (Exception ignored) {
            // jcifs 版本差异：无 getNtStatus 时忽略，仍会显示 message
        }
        return "";
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m.replace("\n", " ").trim();
    }

    private static String buildDiag(String summary, StringBuilder detail) {
        return summary + "\n\n尝试过的凭据策略:\n" + detail;
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
