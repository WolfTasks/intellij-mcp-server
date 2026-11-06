import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.3.0"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

// Configure automatic toolchain download
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    constraints {
        // The IntelliJ test framework (test-framework-core 241.x) pulls jackson 2.16.0, which has
        // known CVEs. Test classpath only — nothing of this ships in the plugin ZIP.
        listOf("jackson-core", "jackson-databind").forEach { artifact ->
            testImplementation("com.fasterxml.jackson.core:$artifact:2.18.12") {
                because("jackson < 2.18.11 has known CVEs (transitive via IntelliJ test-framework)")
            }
        }
    }
}

tasks.test {
    useJUnit()
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("")   // no upper bound — works on 2024.1+
    }
}
