package com.example.mcpserver.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class ExternalDepsTool : McpTool {
    override val name = "external_dependencies"
    override val description = "Lists external dependencies from pom.xml, build.gradle, or requirements.txt"
    override val inputSchema = buildJsonObject { put("type", "object") }

    override fun execute(arguments: JsonObject, project: Project): JsonElement {
        return ReadAction.compute<JsonElement, Exception> {
            val basePath = project.basePath
                ?: return@compute buildJsonObject { put("error", "Project has no base path") }
            val base = LocalFileSystem.getInstance().findFileByPath(basePath)
                ?: return@compute buildJsonObject { put("error", "Could not access project directory") }

            val deps = mutableListOf<JsonObject>()

            base.findChild("pom.xml")?.let { vf ->
                deps += parsePomXml(String(vf.contentsToByteArray()))
            }
            base.findChild("requirements.txt")?.let { vf ->
                deps += parseRequirementsTxt(String(vf.contentsToByteArray()))
            }
            base.findChild("build.gradle")?.let { vf ->
                deps += parseGradleGroovy(String(vf.contentsToByteArray()))
            }
            base.findChild("build.gradle.kts")?.let { vf ->
                deps += parseGradleKts(String(vf.contentsToByteArray()))
            }

            buildJsonObject {
                put("dependencies", buildJsonArray { deps.forEach { add(it) } })
            }
        }
    }

    fun parsePomXml(content: String): List<JsonObject> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val doc = factory.newDocumentBuilder().parse(content.byteInputStream())
            val nodes = doc.getElementsByTagName("dependency")
            (0 until nodes.length).map { i ->
                val dep = nodes.item(i) as Element
                buildJsonObject {
                    val group = dep.getElementsByTagName("groupId").item(0)?.textContent ?: ""
                    val artifact = dep.getElementsByTagName("artifactId").item(0)?.textContent ?: ""
                    put("name", "$group:$artifact")
                    put("version", dep.getElementsByTagName("version").item(0)?.textContent ?: "")
                    put("scope", dep.getElementsByTagName("scope").item(0)?.textContent ?: "compile")
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseRequirementsTxt(content: String): List<JsonObject> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val match = Regex("""^([A-Za-z0-9_\-]+)([=><!]=?[\d.]+.*)?""").find(line)
                buildJsonObject {
                    put("name", match?.groupValues?.get(1) ?: line)
                    val versionSpec = match?.groupValues?.get(2) ?: ""
                    put("version", if (versionSpec.startsWith("==")) versionSpec.removePrefix("==") else versionSpec)
                    put("scope", "runtime")
                }
            }
    }

    private fun parseGradleGroovy(content: String): List<JsonObject> =
        parseGradleDeps(content, Regex("""(?:implementation|api|testImplementation|runtimeOnly)\s+['"]([^:'"]+):([^:'"]+):([^'"]+)['"]"""))

    private fun parseGradleKts(content: String): List<JsonObject> =
        parseGradleDeps(content, Regex("""(?:implementation|api|testImplementation|runtimeOnly)\s*\(\s*"([^:]+):([^:]+):([^"]+)"\s*\)"""))

    private fun parseGradleDeps(content: String, regex: Regex): List<JsonObject> =
        regex.findAll(content).map { m ->
            buildJsonObject {
                put("name", "${m.groupValues[1]}:${m.groupValues[2]}")
                put("version", m.groupValues[3])
                put("scope", "compile")
            }
        }.toList()
}
