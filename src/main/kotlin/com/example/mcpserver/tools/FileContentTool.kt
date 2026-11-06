package com.example.mcpserver.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.*
import java.nio.file.InvalidPathException
import java.nio.file.Path

class FileContentTool : McpTool {
    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private val LOG = Logger.getInstance(FileContentTool::class.java)
    }

    override val name = "read_file"
    override val description = "Reads the text content of a source file inside the project by absolute path"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path to a file inside the project")
            }
        }
        put("required", buildJsonArray { add("path") })
    }

    override fun execute(arguments: JsonObject, project: Project): JsonElement {
        val path = arguments["path"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "Missing required argument: path") }

        // Resolve "..", relative segments and symlinks first, so the project check below
        // applies to the file that would actually be read.
        val realPath = try {
            Path.of(path).toRealPath()
        } catch (e: InvalidPathException) {
            return buildJsonObject { put("error", "Invalid path: $path") }
        } catch (e: Exception) {
            return buildJsonObject { put("error", "File not found: $path") }
        }

        return ReadAction.compute<JsonElement, Exception> {
            val vf = LocalFileSystem.getInstance().findFileByNioFile(realPath)
                ?: return@compute buildJsonObject { put("error", "File not found: $path") }
            if (vf.isDirectory) {
                return@compute buildJsonObject { put("error", "Not a file: $path") }
            }
            if (!ProjectFileIndex.getInstance(project).isInContent(vf)) {
                return@compute buildJsonObject { put("error", "Path outside project: $path") }
            }
            if (vf.length > MAX_FILE_BYTES) {
                return@compute buildJsonObject { put("error", "File too large (limit ${MAX_FILE_BYTES / 1024 / 1024} MiB): $path") }
            }
            val content = try {
                String(vf.contentsToByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                LOG.warn("read_file failed for $path", e)
                return@compute buildJsonObject { put("error", "Could not read file: $path") }
            }
            buildJsonObject { put("content", content) }
        }
    }
}
