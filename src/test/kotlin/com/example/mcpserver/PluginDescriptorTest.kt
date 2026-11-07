package com.example.mcpserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PluginDescriptorTest {

    private fun notificationGroupIds(): List<String> {
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
            ?: error("META-INF/plugin.xml not on the test classpath")
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val nodes = doc.getElementsByTagName("notificationGroup")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("id").nodeValue }
    }

    @Test
    fun `plugin xml registers the notification group the service uses`() {
        assertEquals(listOf(McpPluginService.NOTIFICATION_GROUP_ID), notificationGroupIds())
    }

    @Test
    fun `notification group id does not collide with the bundled JetBrains MCP Server plugin`() {
        // com.intellij.mcpServer registers a group with id "MCP Server"; a second registration is
        // rejected with "Notification group MCP Server is already registered".
        assertNotEquals("MCP Server", McpPluginService.NOTIFICATION_GROUP_ID)
    }
}
