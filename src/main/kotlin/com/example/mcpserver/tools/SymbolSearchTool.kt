package com.example.mcpserver.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.serialization.json.*

class SymbolSearchTool : McpTool {
    override val name = "find_symbol"
    override val description = "Finds a Java class or method by name. Returns FQN, file path, and line number."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Simple or fully qualified name, e.g. 'GroupEngine' or 'com.example.GroupEngine'")
            }
            putJsonObject("kind") {
                put("type", "string")
                put("enum", buildJsonArray { add("class"); add("method") })
                put("description", "Symbol kind. Default: 'class'")
            }
        }
        put("required", buildJsonArray { add("name") })
    }

    override fun execute(arguments: JsonObject, project: Project): JsonElement {
        val name = arguments["name"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "Missing argument: name") }
        val kind = arguments["kind"]?.jsonPrimitive?.content ?: "class"

        if (DumbService.isDumb(project)) {
            return buildJsonObject { put("error", "IntelliJ is still indexing. Try again shortly.") }
        }

        return ReadAction.compute<JsonElement, Exception> {
            val scope = GlobalSearchScope.projectScope(project)
            val results = mutableListOf<JsonObject>()

            when (kind) {
                "class" -> {
                    val byFqn = JavaPsiFacade.getInstance(project).findClass(name, scope)
                    if (byFqn != null) {
                        results += byFqn.toResultJson()
                    } else {
                        PsiShortNamesCache.getInstance(project)
                            .getClassesByName(name, scope)
                            .forEach { results += it.toResultJson() }
                    }
                }
                "method" -> {
                    PsiShortNamesCache.getInstance(project)
                        .getMethodsByName(name, scope)
                        .forEach { results += it.toResultJson() }
                }
                else -> return@compute buildJsonObject {
                    put("error", "Invalid kind '$kind'. Use 'class' or 'method'")
                }
            }

            buildJsonObject {
                put("results", buildJsonArray { results.forEach { add(it) } })
            }
        }
    }

    private fun PsiClass.toResultJson(): JsonObject {
        val vf = containingFile?.virtualFile
        val line = containingFile?.viewProvider?.document
            ?.getLineNumber(textOffset)?.plus(1) ?: -1
        return buildJsonObject {
            put("fqn", qualifiedName ?: name ?: "unknown")
            put("file", vf?.path ?: "unknown")
            put("line", line)
        }
    }

    private fun PsiMethod.toResultJson(): JsonObject {
        val vf = containingFile?.virtualFile
        val line = containingFile?.viewProvider?.document
            ?.getLineNumber(textOffset)?.plus(1) ?: -1
        return buildJsonObject {
            put("fqn", "${containingClass?.qualifiedName ?: "unknown"}.${name}")
            put("file", vf?.path ?: "unknown")
            put("line", line)
        }
    }
}
