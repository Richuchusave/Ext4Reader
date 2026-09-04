package com.ext4reader.copy

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import ext4reader.ext4.Ext4Fs
import ext4reader.ext4.listDir
import ext4reader.ext4.readInode
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

    suspend fun copyRecursively(
        fs: Ext4Fs,
        srcInode: Long,
        srcName: String,
        destDir: DocumentFile,
        resolver: ContentResolver,
        onProgress: (copiedBytes: Long, path: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Long = withContext(Dispatchers.IO) {
        var total = 0L
        fun checkCancelled() {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("copy cancelled")
            ensureActive()
        }

        suspend fun copyOne(inode: Long, name: String, into: DocumentFile, path: String): Long {
            checkCancelled()
            val stat = fs.readInode(inode)
            return when {
                stat.isDir() -> {
                    var dir = into.findFile(name)?.takeIf { it.isDirectory }
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
                    total += text.size
                    onProgress(total, "$path -> $target")
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
                            total += chunk.size
                            onProgress(total, path)
                        }
                        outs.flush()
                    }
                    sum
                }
            }
        }

        copyOne(srcInode, srcName, destDir, srcName)
        total
    }
}
