package com.bigsinger.tvinstaller.data;

public class DeviceInfo {
    private final String name;
    private final String address;

    public DeviceInfo(String name, String address) {
        this.name = name;
        this.address = address;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }
}

