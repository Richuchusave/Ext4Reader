package com.ext4reader.copy

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import ext4reader.ext4.Ext4Fs
import ext4reader.ext4.listDir
import ext4reader.ext4.readInode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Streams ext4 content into a SAF tree using the real core-ext4 API:
 * Ext4Fs(dev, partStartBytes).readInode/listDir/readFileBytes/readlink.
 */
object CopyWorker {

    const val CHUNK_BYTES = 256 * 1024L

    data class CopyTotals(val bytes: Long, val files: Long)

    sealed interface CopyEvent {
        data class Progress(
            val copiedBytes: Long,
            val filesDone: Long,
            val currentPath: String,
        ) : CopyEvent

        data class Done(
            val totalBytes: Long,
            val files: Long,
        ) : CopyEvent

        data class Failed(
            val path: String,
            val message: String,
        ) : CopyEvent
    }

    /**
     * Metadata-only walk: sums regular-file sizes + symlink link-text bytes.
     * Skips "." / "..", guards cycles with a visited-inode set,
     * treats per-node errors as size 0 so measuring never aborts the copy.
     */
    suspend fun measureFs(fs: Ext4Fs, inode: Long): CopyTotals = withContext(Dispatchers.IO) {
        val visited = HashSet<Long>()

        suspend fun walk(num: Long): CopyTotals {
            if (!visited.add(num)) return CopyTotals(0L, 0L)
            ensureActive()
            val stat = try {
                fs.readInode(num)
            } catch (_: Throwable) {
                return CopyTotals(0L, 0L)
            }
            return try {
                when {
                    stat.isDir() -> {
                        var bytes = 0L
                        var files = 0L
                        val kids = try {
                            fs.listDir(num)
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        for (child in kids) {
                            if (child.name == "." || child.name == "..") continue
                            ensureActive()
                            try {
                                val sub = walk(child.inode)
                                bytes += sub.bytes
                                files += sub.files
                            } catch (_: Throwable) {
                                // per-node error -> treat as 0, keep walking siblings
                            }
                        }
                        CopyTotals(bytes, files)
                    }
                    stat.isSymlink() -> {
                        try {
                            val target = fs.readlink(num)
                            CopyTotals(target.toByteArray().size.toLong(), 1L)
                        } catch (_: Throwable) {
                            CopyTotals(0L, 0L)
                        }
                    }
                    else -> CopyTotals(stat.size, 1L)
                }
            } catch (_: Throwable) {
                CopyTotals(0L, 0L)
            }
        }

        walk(inode)
    }

    /**
     * Same streaming logic as [copyRecursively] (256KB chunks via
     * readFileBytes(inode, off, want), dirs recurse, symlinks become
     * "name.link.txt", per-file counting) but emits [CopyEvent.Progress]
     * along the way and [CopyEvent.Done] at the end.
     * On failure emits [CopyEvent.Failed] then rethrows (cancellation is
     * rethrown without a Failed event).
     */
    suspend fun copyWithProgress(
        fs: Ext4Fs,
        srcInode: Long,
        srcName: String,
        destDir: DocumentFile,
        resolver: ContentResolver,
        onEvent: (CopyEvent) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Long = withContext(Dispatchers.IO) {
        var totalBytes = 0L
        var filesDone = 0L
        var currentPath = srcName
        val visited = HashSet<Long>()

        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("copy cancelled")
            ensureActive()
        }

        fun emitProgress(path: String) {
            onEvent(CopyEvent.Progress(totalBytes, filesDone, path))
        }

        suspend fun copyOne(inode: Long, name: String, into: DocumentFile, path: String): Long {
            currentPath = path
            checkCancelled()
            // Cycle-guard: never visit the same inode twice (dir loops / hardlinks).
            if (!visited.add(inode)) return 0L
            val stat = fs.readInode(inode)
            return when {
                stat.isDir() -> {
                    val dir = into.findFile(name)?.takeIf { it.isDirectory }
                        ?: into.createDirectory(name)
                        ?: throw IOException("mkdir failed: $path")
                    var sum = 0L
                    for (child in fs.listDir(inode)) {
                        if (child.name == "." || child.name == "..") continue
                        sum += copyOne(child.inode, child.name, dir, "$path/${child.name}")
                    }
                    sum
                }
                stat.isSymlink() -> {
                    val target = fs.readlink(inode)
                    val text = target.toByteArray()
                    val linkName = "$name.link.txt"
                    into.findFile(linkName)?.takeIf { it.isFile }?.delete()
                    val out = into.createFile("text/plain", linkName)
                        ?: throw IOException("create failed: $path")
                    resolver.openOutputStream(out.uri, "w").use { outs ->
                        requireNotNull(outs) { "no output stream: $path" }
                        outs.write(text)
                    }
                    totalBytes += text.size
                    filesDone += 1
                    emitProgress("$path -> $target")
                    text.size.toLong()
                }
                else -> {
                    // regular file (and anything else readable): stream in chunks
                    into.findFile(name)?.takeIf { it.isFile }?.delete()
                    val out = into.createFile("application/octet-stream", name)
                        ?: throw IOException("create failed: $path")
                    var sum = 0L
                    resolver.openOutputStream(out.uri, "w").use { outs ->
                        requireNotNull(outs) { "no output stream: $path" }
                        var off = 0L
                        while (off < stat.size) {
                            checkCancelled()
                            val want = minOf(CHUNK_BYTES, stat.size - off)
                            val chunk = fs.readFileBytes(inode, off, want)
                            if (chunk.isEmpty()) break
                            outs.write(chunk)
                            off += chunk.size
                            sum += chunk.size
                            totalBytes += chunk.size
                            emitProgress(path)
                        }
                        outs.flush()
                    }
                    filesDone += 1
                    emitProgress(path)
                    sum
                }
            }
        }

        try {
            copyOne(srcInode, srcName, destDir, srcName)
            onEvent(CopyEvent.Done(totalBytes, filesDone))
            totalBytes
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            try {
                onEvent(CopyEvent.Failed(currentPath, t.message ?: t.toString()))
            } catch (_: Throwable) {
                // never mask the original failure
            }
            throw t
        }
    }

    suspend fun copyRecursively(
        fs: Ext4Fs,
        srcInode: Long,
        srcName: String,
        destDir: DocumentFile,
        resolver: ContentResolver,
        onProgress: (copiedBytes: Long, path: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Long {
        var last = 0L
        val total = copyWithProgress(
            fs = fs,
            srcInode = srcInode,
            srcName = srcName,
            destDir = destDir,
            resolver = resolver,
            onEvent = { ev ->
                when (ev) {
                    is CopyEvent.Progress -> {
                        last = ev.copiedBytes
                        onProgress(ev.copiedBytes, ev.currentPath)
                    }
                    is CopyEvent.Done -> {
                        last = ev.totalBytes
                    }
                    is CopyEvent.Failed -> Unit
                }
            },
            isCancelled = isCancelled,
        )
        // copyWithProgress already returns the total; `last` mirrors the final event.
        return total.also { last = it }
    }
}

/** Top-level aliases so callers can use either `CopyTotals` or `CopyWorker.CopyTotals`. */
typealias CopyTotals = CopyWorker.CopyTotals
typealias CopyEvent = CopyWorker.CopyEvent
