package com.example.mcpserver.testutil

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import java.io.File
import java.nio.file.Files

/**
 * Light platform fixtures keep files in an in-memory `temp://` VFS, which tools that resolve
 * absolute paths via [LocalFileSystem] cannot see. This writes a real file on disk, registers it
 * in the local VFS and grants the test access to it. Cleaned up together with [disposable].
 */
fun writeLocalFile(disposable: Disposable, name: String, content: String): File {
    val dir = Files.createTempDirectory("mcp-test").toFile()
    Disposer.register(disposable) { dir.deleteRecursively() }
    VfsRootAccess.allowRootAccess(disposable, dir.path)
    val file = File(dir, name).apply { writeText(content) }
    checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)) { "VFS cannot see $file" }
    return file
}
