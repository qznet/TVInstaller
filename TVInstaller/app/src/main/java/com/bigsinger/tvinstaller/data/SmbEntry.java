package com.bigsinger.tvinstaller.data;

public class SmbEntry {
    private final String name;
    private final String url;
    private final boolean directory;
    private final long size;
    private final long modified;

    public SmbEntry(String name, String url, boolean directory, long size, long modified) {
        this.name = name;
        this.url = url;
        this.directory = directory;
        this.size = size;
        this.modified = modified;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getModified() {
        return modified;
    }
}

