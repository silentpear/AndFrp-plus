# AndFrp+

AndFrp+ — a modern Material 3 file transfer client for Android: FTP, FTPS, SFTP, SCP, WebDAV and SMB, with every feature AndFTP ever had, plus a system-level `DocumentsProvider` that lets **any** app read and write your remote files.

> Status: early development. The UI shell, protocol layer, transfer engine, sync planner and documents provider are implemented; compile verification is pending the first CI run.

## Why another transfer client?

AndFTP is still the reference feature set for Android, but its UI is from another decade and it never integrated with the system. AndFrp+ keeps the feature list and modernises everything else:

| | AndFTP | AndFrp+ |
|---|---|---|
| Material You dynamic colour | — | ✅ |
| FTP / FTPS / SFTP / SCP | ✅ | ✅ |
| WebDAV / SMB | — | ✅ |
| Resume, queue, concurrency | partial | ✅ |
| Internal text editor with save-back | — | ✅ |
| Folder mirror sync with dry-run preview | — | ✅ |
| **Registered in the system file picker** | — | ✅ |

The last row is the point: once a connection exists, it appears in Android's storage picker as a drive. A video player can stream off your NAS, an office app can open and save a remote document, the gallery can import straight to the server. No FTP app on Android does this.

## Feature set

**Protocols** — FTP, FTPS (explicit), FTPS (implicit), SFTP/SSH2, SCP, WebDAV, SMB2/3.
**Auth** — password, SSH private key (OpenSSH & PEM, with passphrase), anonymous, keyboard-interactive, host-key fingerprint pinning.
**Browsing** — single-column drill-down, breadcrumb/up, sort, hidden files, multi-select, rename, recursive delete, chmod, properties panel, recursive search, remote file editing.
**Transfers** — bidirectional queue, resume, pause/resume/cancel/retry with exponential backoff, tunable concurrency (1–8), conflict policy (overwrite/skip/rename/ask), per-item progress, overall progress ring, transfer history, Wi-Fi-only guard, keep-screen-on.
**Sync** — one-way mirror either direction or two-way, dry-run plan before execution, optional deletion of extras.
**Extras** — Material You dynamic colour + custom dark mode, biometric app lock, encrypted credential storage (AES-GCM via Android Keystore), QR-ready connection model, deep links (`sftp://`, `ftp://`, `smb://` …), share-target uploads.

## Building

Developed **without a PC** — the project targets building on-device and in CI.

### GitHub Actions (recommended)

Push to `main`; the workflow builds a debug and a release APK and uploads both as artifacts. Push a `v*` tag to publish them as a release.

### Locally (Termux, aarch64)

The stock Android SDK ships x86-64 `aapt2`/`zipalign`, so Termux needs the packaged aarch64 builds:

```bash
pkg install aapt2 openjdk-21
export ANDROID_HOME=$HOME/android-sdk        # platform 35/36 + build-tools
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=$(command -v aapt2)
```

The full NDK-based build requires ~5 GB of free space; `/data` on a phone usually does not have it. CI does.

## Architecture

```
app/src/main/java/io/github/ferryapp/ferry/
├── data/         models, connection/credential/history/sync stores (JSON on disk)
├── protocol/     RemoteFileSystem + SFTP, FTP(S), WebDAV, SMB implementations
├── transfer/     TransferManager (queue, resume, retry, conflict policy), SyncPlanner
├── documents/    FerryDocumentsProvider — the system file-picker integration
└── ui/           Compose + Material 3 screens, state-driven navigation
```

Everything is coroutine-based; protocol clients are pooled per connection and closed on demand.

## Roadmap

- [ ] First green CI build
- [ ] S3 / MinIO backend
- [ ] Scheduled sync via WorkManager
- [ ] QR code connection sharing
- [ ] Home-screen widget: one-tap folder transfer
- [ ] Tablet / foldable two-pane layout

## Licence

MIT
