import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Security floors for the BUILD-SCRIPT classpath only (IntelliJ Platform Gradle Plugin internals:
// jackson, jsoup). Nothing here ships in the plugin ZIP. A dependency is only ever moved UP to the
// floor, never down. Remove an entry once the Gradle plugin ships a fixed version.
buildscript {
    val floors = mapOf(
        "com.fasterxml.jackson.core:jackson-core" to "2.21.7",
        "com.fasterxml.jackson.core:jackson-databind" to "2.21.7",
        "com.fasterxml.jackson.core:jackson-annotations" to "2.21",
        "org.jsoup:jsoup" to "1.23.2",
    )
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    fun isLower(a: String, b: String): Boolean {
        val (x, y) = parts(a) to parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d < 0
        }
        return false
    }
    configurations.classpath {
        resolutionStrategy.eachDependency {
            val floor = floors["${requested.group}:${requested.name}"] ?: return@eachDependency
            val current = requested.version ?: return@eachDependency
            if (current.isNotEmpty() && isLower(current, floor)) {
                useVersion(floor)
                because("Build-tooling CVE floor (see top of build.gradle.kts)")
            }
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
    kotlin("plugin.serialization") version "2.4.21"
}

// Oldest supported build; shared by patchPluginXml and the custom-repository descriptor.
val supportedSinceBuild = "243"

group = "io.github.wolftasks"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

// Configure automatic toolchain download
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

kotlin {
    jvmToolchain(21)   // IntelliJ 2024.2+ runs on and requires Java 21
    compilerOptions {
        // The IDE provides the Kotlin stdlib at runtime (kotlin.stdlib.default.dependency=false),
        // so our code must not call stdlib API newer than the oldest supported IDE bundles
        // (sinceBuild 243 = 2024.3 ships Kotlin 2.0.21; Kotlin 2.4 can no longer target the 1.9 API of 2024.1/2024.2).
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_2   // 2.0 is deprecated; language level may exceed apiVersion
    }
}

configurations.runtimeClasspath {
    // kotlinx-serialization pulls kotlin-stdlib (+ jetbrains annotations) transitively. The IDE
    // already provides both; bundling a second stdlib in the plugin ZIP causes class conflicts.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation("junit:junit:4.13.2")
    constraints {
        // The IntelliJ test framework (test-framework-core 243.x) pulls jackson 2.17.x, which has
        // known CVEs. Test classpath only — nothing of this ships in the plugin ZIP.
        // Also the IntelliJ Platform Gradle Plugin's own test configurations
        // (intellijPlatformTestDependencies → intellijPlatformTestClasspath), which bypass testImplementation.
        listOf("testImplementation", "intellijPlatformTestDependencies").forEach { configuration ->
            listOf("jackson-core", "jackson-databind").forEach { artifact ->
                add(configuration, "com.fasterxml.jackson.core:$artifact:2.18.12") {
                    because("jackson < 2.18.11 has known CVEs (transitive via IntelliJ test-framework)")
                }
            }
            add(configuration, "org.assertj:assertj-core:3.27.7") {
                because("assertj-core <= 3.27.6 has a known CVE (transitive via IntelliJ test-framework)")
            }
        }
    }
}

tasks.test {
    useJUnit()
    // Keep tests away from the real ~/.intellij-mcp-server.
    systemProperty("intellij.mcp.server.home", layout.buildDirectory.dir("tmp/test-mcp-home").get().asFile.absolutePath)
}

intellijPlatform {
    pluginVerification {
        ides {
            // Oldest supported build (sinceBuild) plus the releases the plugin recommends for sinceBuild..current.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
            recommended()
        }
        // Marketplace naming rules only; this plugin is not published there.
        freeArgs = listOf("-mute", "TemplateWordInPluginName")
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set(supportedSinceBuild)
        untilBuild.set(provider { null })   // no upper bound — works on 2024.3+
    }
}

// Bundle the stdio proxy (source of truth lives in proxy/ so its Python tests run without Gradle).
tasks.processResources {
    from("proxy/intellij_mcp_proxy.py") { into("proxy") }
}

// Custom plugin repository descriptor (Settings -> Plugins -> Manage Plugin Repositories). Attached to the
// GitHub release next to the ZIP; the URL points at the ZIP of the same tag. The plugin id and vendor are
// read from plugin.xml (single source of truth). Configuration-cache friendly: only captured values at execution time.
val pluginXmlFile = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile
val updatePluginsXmlFile = layout.buildDirectory.file("distributions/updatePlugins.xml").get().asFile
val pluginVersion = project.version.toString()
tasks.register("generateUpdatePluginsXml") {
    group = "intellij platform"
    description = "Generates updatePlugins.xml for the GitHub-hosted custom plugin repository."
    // Copy script-level values into locals: doLast must not reference the script object (configuration cache).
    val pluginXml = pluginXmlFile
    val output = updatePluginsXmlFile
    val version = pluginVersion
    val since = supportedSinceBuild
    inputs.file(pluginXml)
    inputs.property("version", version)
    inputs.property("sinceBuild", since)
    outputs.file(output)
    doLast {
        val xml = pluginXml.readText(Charsets.UTF_8)
        val id = Regex("<id>\\s*(.+?)\\s*</id>").find(xml)?.groupValues?.get(1) ?: error("No <id> in plugin.xml")
        val vendor = Regex("<vendor[^>]*>\\s*(.+?)\\s*</vendor>").find(xml)?.groupValues?.get(1) ?: error("No <vendor> in plugin.xml")
        fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val url = "https://github.com/WolfTasks/intellij-mcp-server/releases/download/v$version/intellij-mcp-server-$version.zip"
        output.parentFile.mkdirs()
        output.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="${esc(id)}" url="${esc(url)}" version="${esc(version)}">
    <idea-version since-build="${esc(since)}"/>
    <name>IntelliJ MCP Server</name>
    <vendor>${esc(vendor)}</vendor>
  </plugin>
</plugins>
""".replace("\r", ""),
            Charsets.UTF_8,
        )
    }
}
