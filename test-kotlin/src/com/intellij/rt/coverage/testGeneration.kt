/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.rt.coverage

import java.io.File
import kotlin.reflect.KClass

private val testFileNames = listOf("test.kt", "Test.java")

private fun listTestNames(name: String, root: File, ignoredTests: List<String>, result: MutableList<String>) {
    if (name in ignoredTests) return
    val children = root.list()?.sorted()?.map { File(root, it) }
        ?: error("Cannot list files in ${root.path}")

    if (children.all { !it.isDirectory }) {
        result.add(name)
        return
    }

    val prefix = if (name.isEmpty()) "" else "$name."
    for (child in children) {
        if (child.isDirectory) {
            listTestNames("$prefix${child.name}", child, ignoredTests, result)
        } else {
            check(child.name !in testFileNames) { "Test source should be located in a separate subfolder: ${child.path}." }
        }
    }
}

/**
 * Generate test methods code from test sources located in [testDataRoot].
 *
 * Test method includes Test annotation and test method call. Test name is capitalized in the test method name.
 * A test can be ignored by adding '// ignore: REASON' comment in the main source file.
 *
 * @param ignoredTests list of test names that should be ignored, for example "custom" tests
 */
fun generateTests(testDataRoot: File, ignoredTests: List<String>): String {
    require(testDataRoot.isDirectory)
    val testNames = mutableListOf<String>()
    listTestNames("", testDataRoot, ignoredTests, testNames)
    return testNames.joinToString("\n") { s ->
        val test = getTestFile(s)
        var ignore = ""
        val ignored = object : Matcher(Regex("// ignore: (.*)\$"), 1) {
            override fun onMatchFound(line: Int, match: String) {
                ignore = "    @Ignore(\"$match\")\n"
            }
        }
        processFile(test.file, ignored)

        val capitalized = s.split('.').joinToString("") { it.capitalize() }
        "    @Test\n$ignore    fun test$capitalized() = test(\"$s\")\n"
    }
}

/**
 * Replace test between two substrings equal to [marker] with generated tests code.
 */
fun replaceGeneratedTests(tests: String, testFile: File, marker: String) {
    val fileContent = testFile.readText()
    val start = fileContent.indexOf(marker).also { check(it >= 0) }.let { it + marker.length }
    val end = fileContent.lastIndexOf(marker).also { check(it >= 0) }
    val newContent = """
        |${fileContent.substring(0, start)}
        |
        |$tests
        |${fileContent.substring(end)}
    """.trimMargin()
    testFile.writeText(newContent)
}

private fun getTestFile(ktClass: KClass<*>): File {
    val testClassName = ktClass.qualifiedName!!.split('.').toTypedArray()
    testClassName[testClassName.lastIndex] += ".kt"
    return pathToFile("src", *testClassName)
}

/**
 * test-kotlin should be the test directory.
 */
fun main() {
    val ignoredDirectories = listOf("custom")
    val tests = generateTests(File("src", TEST_PACKAGE), ignoredDirectories)

    val testFile = getTestFile(CoverageRunTest::class)
    val marker = "    //===GENERATED TESTS==="
    replaceGeneratedTests(tests, testFile, marker)
}
