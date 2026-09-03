# Ext4Reader — rootless ext4 reader for Android

Read ext4 USB sticks on an unrooted phone (OTG) and copy files out via the
Storage Access Framework. No root, no kernel mount: SCSI over USB
(Bulk-Only Transport) + pure-Kotlin ext4 parsing.

Modules:

- `:core-blocks` — `BlockDevice` abstraction (JVM, tested).
- `:core-partition` — MBR/GPT scan (`collectCandidates`) + ext4 superblock probe (`probeExt4`).
- `:core-ext4` — read-only ext4 filesystem (provides `Ext4Fs`, see contract below).
- `:app` — Android Studio app module (Compose UI, USB host, SAF copy).

## Requirements

- Android Studio Ladybug (2024.2+) or newer, with Android SDK Platform 34.
- JDK 17 (Gradle toolchain / `org.gradle.java.home`).
- A phone with USB-OTG host mode (tested target: Samsung A07, Android 16).
- USB stick with an ext4 partition, OTG adapter if needed.
- A USB stick speaking **Bulk-Only Transport** (most plain sticks, e.g. Kingston
  DataTraveler). UASP-only enclosures are **not** supported.

## Open in Android Studio

1. Open this folder (`Ext4Reader/`) as a project.
2. Let Gradle sync (`settings.gradle.kts` includes `:app` plus the three core modules).
3. Select the `app` run configuration and your device.

## Build from the command line

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

(No gradle wrapper is checked in from this Termux scaffold — run
`gradle wrapper --gradle-version 8.9` once on a machine with Gradle, or build
from Android Studio which supplies Gradle.)

## OTG flow (real stick)

1. Plug the stick into the phone via OTG.
2. If Android's Files app auto-mounts/grabs it: **eject it from Files first** —
   `UsbManager.openDevice()` fails while the system holds the device.
3. Open Ext4Reader → tap your drive in the list → **Allow** the permission prompt.
4. Partition list shows `collectCandidates` + `probeExt4` results; ext4 rows are badged.
5. Tap an ext4 partition → browse from inode 2 (`/`), tap dirs to descend.
6. Tap **Copy** (per file/dir, or **Copy folder**) → pick a destination with the
   system folder picker (`ACTION_OPEN_DOCUMENT_TREE`) → watch progress, cancel anytime.

## .img test flow (no hardware)

1. In the app's picker screen tap **Test .img file**.
2. Choose any raw disk image (`.img` with MBR/GPT + ext4 inside).
3. The app copies it to its cache dir, wraps it in the JVM `FileBlockDevice`,
   and jumps straight to the partition list. Same browser/copy flow as USB.

## core-ext4 contract (what `:app` expects)

`:core-ext4` must expose (JVM Kotlin, package `ext4reader.ext4`):

```kotlin
enum class FileType { REG_FILE, DIR, SYMLINK, OTHER }
data class DirEntry(val name: String, val inode: Long, val type: FileType, val size: Long)
data class NodeStat(val type: FileType, val size: Long)

class Ext4Fs(dev: BlockDevice) : Closeable {
    fun listDir(inode: Long): List<DirEntry>
    fun stat(inode: Long): NodeStat
    fun openRead(inode: Long): InputStream
    fun readSymlink(inode: Long): String
}
```

The app mounts one partition by wrapping the USB device in a read-only
`PartitionSliceDevice` (offset `startLba`, see `AppNav.kt`) and passing it to
`Ext4Fs`. Symlinks are copied as `<name>.link.txt` pointer files.

## Limits / out of scope

- **BOT only**: USB Mass Storage Bulk-Only (`interface class 8` + bulk IN/OUT).
  UASP-only bridges are rejected with an "unsupported device" message.
- **No LUKS / LVM / dm-crypt / RAID**: encrypted or stacked volumes are probed as
  "no magic" and cannot be browsed.
- Read-only by design: the app never writes to the USB device.
- Filenames are taken as-is from the directory entries; exotic encodings may
  display oddly but still copy.
