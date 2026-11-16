# AndFrp+

**Android 上的现代化文件传输客户端**：支持 FTP / FTPS / SFTP / SCP / WebDAV / SMB，
界面用 Material 3，并且把服务器**接进了系统文件选择器**——任何 App 都能直接读写你的服务器文件。

> 项目地址：https://github.com/silentpear/AndFrp-plus/tree/main
>
> 下载：https://github.com/silentpear/AndFrp-plus/releases

---

## 为什么做这个

AndFTP 的功能清单到今天依然是安卓端的参考标准，但它的界面停留在十年前，而且从未和系统集成。
AndFrp+ 保留了那套功能，把其余部分重做：

| 能力 | AndFTP | AndFrp+ |
|---|---|---|
| FTP / FTPS / SFTP / SCP | ✅ | ✅ |
| WebDAV / SMB | — | ✅ |
| Material You 动态取色 | — | ✅ |
| 断点续传 / 重复重试 / 并发可调 | 部分 | ✅ |
| 图形化权限编辑（3×3 勾选框） | ✅ | ✅ |
| 图形化选择下载目录 | — | ✅ |
| 文件夹镜像同步（执行前预演） | — | ✅ |
| 内置文本编辑器 | — | ✅ |
| **服务器注册进系统文件选择器** | — | ✅ |
| 后台传输保活（前台服务 + 唤醒锁） | 部分 | ✅ |

最后一行是重点：连接添加后，它会出现在系统的存储选择器里。
视频播放器能直接播 NAS 上的片子，WPS 能直接打开并保存服务器上的文档，相册能直接导入到远端目录。

---

## 功能

**协议** — FTP、FTPS（显式）、FTPS（隐式）、SFTP/SSH2、SCP、WebDAV、SMB2/3

**认证** — 密码、SSH 私钥（OpenSSH / PEM，支持口令）、匿名、主机密钥指纹

**浏览** — 单栏下钻、多选操作栏、排序（名称 / 大小 / 修改时间，升降序）、
隐藏文件、搜索、新建文件夹、重命名、递归删除、**图形化 chmod**、属性面板

**传输** — 双向队列、断点续传、失败自动重试（指数退避）、并发 1–8、
暂停 / 继续 / 取消、冲突策略（覆盖 / 跳过 / 重命名 / 询问）、实时速率、传输历史、
**前台服务保活 + CPU 唤醒锁**（切到后台不断线）

**下载目录** — 通过系统「选择文件夹」指定任意位置（Download、SD 卡、U 盘），
凭 SAF 授权写入，绕开 Android 11+ 对共享存储的写限制

**上传** — 顶部 ⬆ 按钮调用系统文件选择器，可多选

**同步** — 本地→远端 / 远端→本地 / 双向镜像，**执行前先预演**列出将要变动的文件

**系统集成** — `DocumentsProvider`（服务器变成系统存储）、
分享目标上传、`sftp://` `ftp://` `smb://` 深链

**安全与体验** — 凭据双写存储（加密不可用时自动回退）、指纹 / 面容解锁、
Material You 动态取色、深色模式、无广告无遥测

---

## 安装

到 [Releases](https://github.com/silentpear/AndFrp-plus/releases) 下载：

- `AndFrpPlus-debug.apk` —— 已签名，**直接安装**（推荐）
- `AndFrpPlus-release-unsigned.apk` —— 未签名，需自行签名后才能安装

最低 Android 8.0（API 26），arm64。

---

## 构建

全程在**手机上**开发，构建交给 GitHub Actions。

### GitHub Actions

推送到 `main` 会自动构建；推 `v*` 标签会自动发布 Release。

### 本地（Termux / aarch64）

```bash
pkg install aapt2 openjdk-21
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$(command -v aapt2)
```

---

## 架构

```
app/src/main/java/io/github/silentpear/andfrpplus/
├── data/         数据模型、连接 / 凭据 / 历史 / 同步存储
├── protocol/     RemoteFileSystem 接口 + SFTP / FTP(S) / WebDAV / SMB 实现
├── transfer/     TransferManager（队列、续传、重试、冲突）、SyncPlanner、前台服务
├── documents/    AndFrpDocumentsProvider —— 系统文件选择器集成
└── ui/           Compose + Material 3，状态驱动导航
```

底层协议全部使用成熟开源库：`sshj`、`commons-net`、`jcifs-ng`、`okhttp`。

---

## 已知限制

- 改权限仅 SFTP 支持；FTP / WebDAV / SMB 协议本身无此能力
- 定时同步尚未接入 WorkManager

---

## 许可

MIT
