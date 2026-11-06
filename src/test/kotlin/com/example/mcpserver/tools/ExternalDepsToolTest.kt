package com.example.mcpserver.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExternalDepsToolTest {

    @Test
    fun `parsePom extracts dependency name and version`() {
        val pom = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.junit</groupId>
                  <artifactId>junit</artifactId>
                  <version>4.13.2</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent()
        val tool = ExternalDepsTool()
        val deps = tool.parsePomXml(pom)
        assertEquals(1, deps.size)
        assertEquals("org.junit:junit", deps[0]["name"]?.jsonPrimitive?.content)
        assertEquals("4.13.2", deps[0]["version"]?.jsonPrimitive?.content)
        assertEquals("test", deps[0]["scope"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parsePom defaults scope to compile when absent`() {
        val pom = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>32.0.0-jre</version>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent()
        val deps = ExternalDepsTool().parsePomXml(pom)
        assertEquals("compile", deps[0]["scope"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseRequirementsTxt extracts package and pinned version`() {
        val req = """
            requests==2.31.0
            flask>=2.0
            # a comment
            pytest
        """.trimIndent()
        val tool = ExternalDepsTool()
        val deps = tool.parseRequirementsTxt(req)
        assertEquals(3, deps.size)
        assertEquals("requests", deps[0]["name"]?.jsonPrimitive?.content)
        assertEquals("2.31.0", deps[0]["version"]?.jsonPrimitive?.content)
        assertEquals("flask", deps[1]["name"]?.jsonPrimitive?.content)
        assertEquals(">=2.0", deps[1]["version"]?.jsonPrimitive?.content)
        assertEquals("pytest", deps[2]["name"]?.jsonPrimitive?.content)
        assertEquals("", deps[2]["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parsePom returns empty list for malformed XML`() {
        val deps = ExternalDepsTool().parsePomXml("not xml at all")
        assertTrue(deps.isEmpty())
    }

    @Test
    fun `parsePom does not resolve external entities`() {
        val secret = java.io.File.createTempFile("xxe", ".txt").apply {
            writeText("TOP-SECRET-MARKER")
            deleteOnExit()
        }
        val pom = """
            <?xml version="1.0"?>
            <!DOCTYPE project [ <!ENTITY x SYSTEM "${secret.toURI()}"> ]>
            <project>
              <dependencies>
                <dependency>
                  <groupId>&x;</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent()
        val deps = ExternalDepsTool().parsePomXml(pom)
        assertFalse(deps.toString().contains("TOP-SECRET-MARKER"))
        assertTrue("documents with a DOCTYPE are refused", deps.isEmpty())
    }

    @Test
    fun `parsePom refuses entity expansion bombs`() {
        val pom = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <project><dependencies><dependency><groupId>&c;</groupId><artifactId>a</artifactId></dependency></dependencies></project>
        """.trimIndent()
        assertTrue(ExternalDepsTool().parsePomXml(pom).isEmpty())
    }
}
