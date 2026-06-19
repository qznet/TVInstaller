# SMB Installer

把安卓电视变成「免 U 盘装 App」的利器——通过局域网 Samba 服务浏览并安装 APK。

## 痛点

安卓电视安装第三方 App 的常见困扰：U 盘来回插拔、自带商店应用不全、电视文件管理器不认 APK、远程安装工具频繁停更。

## 方案

```
NAS (SMB共享)  ──── SMB协议 ────►  Android TV
  /APK/                          SMB Installer
  ├─ Kodi.apk                     1. 扫描局域网 SMB 服务
  ├─ VLC.apk                      2. 浏览 APK 文件
  ├─ 当贝.apk                     3. 下载到本地
  └─ ...                          4. 调起系统安装
```

整个流程纯局域网完成，不需要互联网和云服务。

## 快速开始

### 前提

- Android 电视（Android 4.2+，API 17+）
- 同一局域网内有开启 SMB 共享的 NAS 或电脑

### 安装 App

```bash
# 方式一：U 盘安装（仅第一次需要）
下载 SMBInstaller.apk → 拷入 U 盘 → 电视上安装

# 方式二：ADB 投送
adb connect <电视IP>
adb install SMBInstaller.apk
```

### 连接 NAS

1. 打开 App，自动扫描局域网内 SMB 设备（约 5-15 秒）
2. 点击你的 NAS 设备
3. 输入 SMB 用户名和密码（可勾选记住密码）
4. 进入 APK 文件列表，选择要安装的 App

## 功能

- **局域网自动扫描** — 多线程并发探测 445 端口，实时展示发现的设备
- **历史设备记忆** — 关闭 App 后自动记住已连接的设备和目录
- **凭证加密存储** — AES-256 加密密码，不存明文
- **APK 文件过滤** — 只显示文件夹和 .apk 文件，按修改时间降序排列
- **下载+安装** — 流式下载到本地缓存，自动调起系统安装界面
- **TV 遥控器适配** — AndroidX Leanback，全程方向键+OK 完成操作

## 兼容性

| 系统版本 | 适配处理 |
|---------|---------|
| Android 4.2~6.0 | 直接 `Uri.fromFile()` |
| Android 7.0+ | `FileProvider` 生成 content URI |
| Android 8.0+ | 检查并引导"安装未知应用"权限 |
| Android 10+ | 分区存储适配，使用外部缓存目录 |
| Android TV | Leanback 焦点导航，全程遥控器操作 |

## 项目结构

```
TVInstaller/
├── doc/
│   ├── TVInstaller-spec.md          # 技术规格书
│   └── TVInstaller-prototype.html   # 交互原型 (TV/Phone)
├── TVInstaller/
│   └── app/src/main/java/com/bigsinger/tvinstaller/
│       ├── ui/
│       │   ├── ScanActivity.java      # 主页面：设备扫描与列表
│       │   └── FileListActivity.java  # 文件浏览页
│       ├── net/
│       │   ├── LanScanner.java        # 局域网 445 端口扫描
│       │   └── SmbRepository.java     # JCIFS-NG SMB 操作封装
│       ├── security/
│       │   ├── Credential.java        # 凭证模型
│       │   └── CredentialStore.java   # AES-256 加密存储
│       ├── service/
│       │   └── ApkDownloadService.java # 后台下载服务
│       ├── adapter/
│       │   ├── DeviceAdapter.java     # 设备列表适配器
│       │   └── FileAdapter.java       # 文件列表适配器
│       └── data/
│           ├── DeviceHistoryStore.java # 历史设备持久化
│           ├── DeviceInfo.java         # 设备信息模型
│           └── SmbEntry.java           # SMB 条目模型
└── README.md
```

## 技术栈

- **开发语言**：Java 8
- **最低 API**：17（Android 4.2）
- **SMB 协议**：JCIFS-NG 2.1.8（纯 Java，支持 SMB v2/v3）
- **TV 适配**：AndroidX Leanback
- **密码加密**：AES-256/CBC/PKCS5Padding

## 构建

用 Android Studio 打开 `TVInstaller/` 目录，Gradle sync 后直接 Build。

## License

基于 JCIFS-NG（GPLv2）构建，应用主体代码按 GPLv2 发布。
