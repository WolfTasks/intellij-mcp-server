package com.example.mcpserver.testutil

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy

/**
 * A do-nothing [Project] for plain JUnit tests of code that only passes the project through.
 * Any method call other than toString/hashCode/equals throws, so accidental use is loud.
 */
fun fakeProject(): Project =
    Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "FakeProject"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> throw UnsupportedOperationException("FakeProject.${method.name}")
        }
    } as Project
