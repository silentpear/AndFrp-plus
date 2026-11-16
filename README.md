# AndFrp+

我自己用的安卓文件传输工具。

起因很简单：手机上想连自己的服务器传点东西，找了一圈，AndFTP 功能最全但界面停在十年前，
其他要么缺协议要么收费。干脆自己写一个，功能照着 AndFTP 抄全，界面用 Material 3 重做，
顺便把 AndFTP 一直没做的事补上——**把服务器塞进系统的文件选择器**。

> 项目地址：https://github.com/silentpear/AndFrp-plus/tree/main
> 下载：https://github.com/silentpear/AndFrp-plus/releases

---

## 为什么值得用

如果你也在手机上连服务器，这个工具多给了你三样东西：

**一、服务器变成系统存储**
添加连接后，它会出现在系统的文件选择器里。视频播放器能直接播服务器上的片子，
WPS 能直接打开并保存远端的文档，相册能直接导入到服务器目录——不用先下载再上传。
这一条目前没有任何安卓 FTP 客户端做到过。

**二、该有的功能一个不少**
FTP / FTPS / SFTP / SCP / WebDAV / SMB 全支持，断点续传、失败重试、并发调节、
冲突策略、文件夹镜像同步、图形化改权限、内置文本编辑器。

**三、界面不别扭**
Material You 动态取色，跟着系统壁纸走；深色模式、指纹锁、无广告无遥测。

---

## 功能清单

**协议**　FTP、FTPS（显式/隐式）、SFTP/SSH2、SCP、WebDAV、SMB2/3

**认证**　密码、SSH 私钥（OpenSSH/PEM，支持口令）、匿名、主机密钥指纹校验

**浏览**　单栏下钻、长按多选、按名称/大小/时间排序（升降序）、隐藏文件、
递归搜索、新建文件夹、重命名、递归删除、**3×3 图形化改权限**、属性面板

**传输**　双向队列、断点续传、指数退避重试、并发 1–8、暂停/继续/取消、
冲突策略（覆盖/跳过/重命名/询问）、实时速率、传输历史、
**前台服务 + 唤醒锁保活**（切后台不断线、锁屏继续跑）

**下载目录**　用系统「选择文件夹」指定任意位置（Download、SD 卡、U 盘），
走 SAF 授权写入，绕开 Android 11+ 对共享存储的写限制

**上传**　顶部 ⬆ 调用系统文件选择器，可多选，自动排队

**同步**　本地→远端 / 远端→本地 / 双向镜像，**执行前先预演**要变动哪些文件

**系统集成**　`DocumentsProvider`、分享目标上传、`sftp://` `ftp://` `smb://` 深链

**安全**　凭据双写存储（加密不可用时自动回退）、指纹/面容解锁

---

## 安装

到 [Releases](https://github.com/silentpear/AndFrp-plus/releases) 下载：

- `AndFrpPlus-debug.apk` —— 已签名，**直接安装**（推荐）
- `AndFrpPlus-release-unsigned.apk` —— 未签名，需要自己签

最低 Android 8.0（API 26），arm64。

---

## 构建

全程在手机上开发，构建交给 GitHub Actions。

推送到 `main` 自动打包；推 `v*` 标签自动发布 Release。

本地构建（Termux，aarch64）：

```bash
pkg install aapt2 openjdk-21
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$(command -v aapt2)
```

---

## 架构

```
app/src/main/java/io/github/silentpear/andfrpplus/
├── data/         数据模型、连接/凭据/历史/同步存储
├── protocol/     RemoteFileSystem 接口 + SFTP/FTP(S)/WebDAV/SMB 实现
├── transfer/     TransferManager（队列、续传、重试、冲突）、SyncPlanner、前台服务
├── documents/    AndFrpDocumentsProvider —— 系统文件选择器集成
└── ui/           Compose + Material 3，状态驱动导航
```

协议层不重复造轮子，用的是 `sshj`、`commons-net`、`jcifs-ng`、`okhttp`。

---

## 已知限制

- 改权限只有 SFTP 支持，FTP/WebDAV/SMB 协议本身没这个能力
- 定时同步还没接 WorkManager，目前只在应用运行时生效
- 只在 arm64 真机上验证过

---

## 许可

MIT
