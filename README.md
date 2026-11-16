# AndFrp+

Android 平台的 FTP / SFTP / WebDAV / SMB 文件传输客户端，Material 3 界面。

支持把服务器注册进系统文件选择器，其他应用可以直接读写远端文件。

## 功能

**协议**：FTP · FTPS（显式/隐式）· SFTP · SCP · WebDAV · SMB2/3

**认证**：密码 · SSH 私钥（OpenSSH/PEM，支持口令）· 匿名 · 主机密钥指纹校验

**文件管理**
- 长按多选，底部操作栏批量下载 / 删除
- 排序：名称 / 大小 / 修改时间，升降序；文件夹置顶
- 递归搜索、新建文件夹、重命名、属性查看
- 图形化改权限（3×3 勾选框，SFTP）

**传输**
- 双向队列、断点续传、失败自动重试、并发 1–8
- 冲突策略：覆盖 / 跳过 / 重命名 / 询问
- 前台服务保活，切后台或锁屏继续传输，进度同步到通知栏

**下载目录**：系统文件夹选择器指定任意位置，走 SAF 授权写入

**上传**：调用系统文件选择器，支持多选

**在线预览**：图片应用内查看（左右滑动切换），视频音频内置播放

**同步**：本地→远端 / 远端→本地 / 双向镜像，执行前预演变动清单

**界面**：Material You 动态取色、深色模式、背景可选纯色/图片/视频、指纹锁

**系统集成**：`DocumentsProvider`、分享上传、`sftp://` `ftp://` `smb://` 深链

无广告、无统计、无账号。

## 安装

到 [Releases](https://github.com/silentpear/AndFrp-plus/releases) 下载：

| 文件 | 说明 |
|---|---|
| `AndFrpPlus-debug.apk` | 已用调试密钥签名，可直接安装 |
| `AndFrpPlus-release-unsigned.apk` | 未签名，需自行签名 |

最低 Android 8.0（API 26），arm64。

## 构建

推送到 `main` 由 GitHub Actions 自动打包；推 `v*` 标签自动发布。

本地构建（Termux / aarch64）：

```bash
pkg install aapt2 openjdk-21
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$(command -v aapt2)
```

## 技术栈

Kotlin · Jetpack Compose · Material 3 · Kotlin Coroutines · kotlinx.serialization

协议实现依赖 `sshj`（SFTP/SCP）、`commons-net`（FTP/FTPS）、`okhttp`（WebDAV）、`jcifs-ng`（SMB）。

```
app/src/main/java/io/github/silentpear/andfrpplus/
├── data/       数据模型与本地存储
├── protocol/   RemoteFileSystem 及四种协议实现
├── transfer/   传输队列、同步规划、前台服务
├── documents/  DocumentsProvider
└── ui/         Compose 界面
```

## 已知限制

- 改权限仅 SFTP 支持
- 定时同步目前只在应用运行时生效
- 仅在 arm64 真机验证

## 许可

MIT
