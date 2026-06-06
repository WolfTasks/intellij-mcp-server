package com.example.mcpserver.tools

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import kotlinx.serialization.json.*

class DiagnosticsTool : McpTool {
    override val name = "diagnostics"
    override val description = "Returns errors and warnings for a specific file, or lists all project files with problems."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute file path. Omit to list all files with problems.")
            }
        }
    }

    override fun execute(arguments: JsonObject, project: Project): JsonElement {
        if (DumbService.isDumb(project)) {
            return buildJsonObject { put("error", "IntelliJ is still indexing. Try again shortly.") }
        }
        val path = arguments["path"]?.jsonPrimitive?.content
        return if (path != null) diagnosticsForFile(path, project) else allProblematicFiles(project)
    }

    private fun diagnosticsForFile(path: String, project: Project): JsonElement {
        return ReadAction.compute<JsonElement, Exception> {
            val vf = LocalFileSystem.getInstance().findFileByPath(path)
                ?: return@compute buildJsonObject { put("error", "File not found: $path") }
            val doc = FileDocumentManager.getInstance().getDocument(vf)
                ?: return@compute buildJsonObject { put("error", "Cannot open document: $path") }

            val markupModel = DocumentMarkupModel.forDocument(doc, project, false)
            val highlights = markupModel?.allHighlighters
                ?.mapNotNull { highlighter ->
                    val info = highlighter.errorStripeTooltip as? HighlightInfo ?: return@mapNotNull null
                    if (info.severity < HighlightSeverity.WARNING) return@mapNotNull null
                    buildJsonObject {
                        put("severity", if (info.severity >= HighlightSeverity.ERROR) "ERROR" else "WARNING")
                        put("message", info.description ?: "")
                        put("file", path)
                        put("line", doc.getLineNumber(highlighter.startOffset) + 1)
                    }
                } ?: emptyList()

            buildJsonObject {
                put("diagnostics", buildJsonArray { highlights.forEach { add(it) } })
            }
        }
    }

    private fun allProblematicFiles(project: Project): JsonElement {
        return ReadAction.compute<JsonElement, Exception> {
            val wolf = WolfTheProblemSolver.getInstance(project)
            val files = mutableListOf<String>()
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                collectProblematicFiles(root, wolf, files)
            }
            buildJsonObject {
                put("problematic_files", buildJsonArray { files.forEach { add(it) } })
            }
        }
    }

    private fun collectProblematicFiles(
        dir: VirtualFile,
        wolf: WolfTheProblemSolver,
        result: MutableList<String>
    ) {
        dir.children.forEach { child ->
            if (child.isDirectory) {
                collectProblematicFiles(child, wolf, result)
            } else if (wolf.isProblemFile(child)) {
                result += child.path
            }
        }
    }
}
