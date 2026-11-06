package com.example.mcpserver.tools

import com.example.mcpserver.testutil.writeLocalFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files

class FileContentToolTest : BasePlatformTestCase() {

    private val tool = FileContentTool()

    private fun read(path: String): JsonObject =
        tool.execute(buildJsonObject { put("path", path) }, project).jsonObject

    /**
     * Light fixtures keep project files in an in-memory temp:// VFS that the tool (which resolves real
     * on-disk paths) can't see. So we write a real file and register its directory as a content root
     * of the light module for the duration of the test.
     */
    private fun inProject(name: String, content: String): File {
        val file = writeLocalFile(testRootDisposable, name, content)
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file.parentFile)!!
        val entry = PsiTestUtil.addContentRoot(module, dir)
        com.intellij.openapi.util.Disposer.register(testRootDisposable) {
            PsiTestUtil.removeContentEntry(module, entry.file!!)
        }
        return file
    }

    fun `test reads file inside project content`() {
        val file = inProject("Hello.java", "class Hello { /* hellö */ }")
        assertEquals("class Hello { /* hellö */ }", read(file.path)["content"]?.jsonPrimitive?.content)
    }

    fun `test rejects file outside project content`() {
        val outside = writeLocalFile(testRootDisposable, "secret.txt", "TOP-SECRET")
        val result = read(outside.path)
        assertNull("must not return content", result["content"])
        assertTrue(result["error"]!!.jsonPrimitive.content.startsWith("Path outside project"))
    }

    fun `test rejects dot-dot escape from project content`() {
        val inside = inProject("Inside.java", "class Inside {}")
        val outside = writeLocalFile(testRootDisposable, "secret.txt", "TOP-SECRET")
        val escaped = inside.parentFile.path + File.separator + ".." + File.separator +
            outside.parentFile.name + File.separator + outside.name
        val result = read(escaped)
        assertNull(result["content"])
        assertTrue(result["error"]!!.jsonPrimitive.content.startsWith("Path outside project"))
    }

    fun `test rejects symlink inside project pointing outside`() {
        val inside = inProject("Inside.java", "class Inside {}")
        val outside = writeLocalFile(testRootDisposable, "secret.txt", "TOP-SECRET")
        val link = File(inside.parentFile, "link.txt").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (e: Exception) {
            return // symlinks need privileges on Windows — covered on Linux CI
        }
        val result = read(link.toString())
        assertNull(result["content"])
        assertTrue(result["error"]!!.jsonPrimitive.content.startsWith("Path outside project"))
    }

    fun `test rejects files above size limit`() {
        val big = inProject("Big.txt", "x".repeat((FileContentTool.MAX_FILE_BYTES + 1).toInt()))
        assertTrue(read(big.path)["error"]!!.jsonPrimitive.content.startsWith("File too large"))
    }

    fun `test rejects directories`() {
        val inside = inProject("Inside.java", "class Inside {}")
        assertTrue(read(inside.parentFile.path)["error"]!!.jsonPrimitive.content.startsWith("Not a file"))
    }

    fun `test returns error for missing file`() {
        assertNotNull(read("/does/not/exist.java")["error"])
    }

    fun `test returns error when path argument missing`() {
        assertNotNull(tool.execute(JsonObject(emptyMap()), project).jsonObject["error"])
    }
}
