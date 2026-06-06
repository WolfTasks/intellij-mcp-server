package com.example.mcpserver.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.*

class FileContentTool : McpTool {
    override val name = "read_file"
    override val description = "Reads the text content of a source file by absolute path"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path to the file")
            }
        }
        put("required", buildJsonArray { add("path") })
    }

    override fun execute(arguments: JsonObject, project: Project): JsonElement {
        val path = arguments["path"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "Missing required argument: path") }

        return ReadAction.compute<JsonElement, Exception> {
            val vf = LocalFileSystem.getInstance().findFileByPath(path)
                ?: return@compute buildJsonObject { put("error", "File not found: $path") }
            val content = try {
                String(vf.contentsToByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                return@compute buildJsonObject { put("error", "Could not read file: ${e.message}") }
            }
            buildJsonObject { put("content", content) }
        }
    }
}
