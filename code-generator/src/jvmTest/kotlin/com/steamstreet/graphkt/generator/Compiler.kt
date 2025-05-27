package com.steamstreet.graphkt.generator

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

/**
 * Compile the provided files.
 */
fun compileKotlinFiles(sourceFiles: List<File>, outputDir: File): Boolean {
    val compiler = K2JVMCompiler()
    val messageCollector = PrintingMessageCollector(System.out, MessageRenderer.PLAIN_FULL_PATHS, true)

    val classesDir = File(outputDir, "classes")
    classesDir.mkdirs()

    val arguments = K2JVMCompilerArguments().apply {
        freeArgs = sourceFiles.map { it.absolutePath }
        destination = classesDir.absolutePath

        // Use the system classpath, which should include the jars from common-runtime and server
        // since we've added them as dependencies in the build.gradle.kts file
        val systemClasspath = System.getProperty("java.class.path")

        // Include the output directory in the classpath so that the compiler can find
        // the generated classes during compilation
        classpath = "$systemClasspath${File.pathSeparator}${outputDir.absolutePath}"

        // Log the classpath for debugging
        println("[DEBUG_LOG] Classpath: $classpath")

        jvmTarget = "17"
        noStdlib = false
        noReflect = false
        moduleName = "test-compilation"
        verbose = true
    }
    val exitCode = compiler.exec(messageCollector, Services.EMPTY, arguments)
    return exitCode == ExitCode.OK
}

fun compileKotlinFiles(outputDir: File): Boolean {
    // Find all generated Kotlin files
    val generatedFiles = outputDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    println("Compiling: ${generatedFiles.joinToString(", ") { it.absolutePath.substringAfter(outputDir.absolutePath) }}")

    // Compile the generated code
    return compileKotlinFiles(generatedFiles, outputDir)
}

/**
 * Validate that the code in the given dir compiles.
 */
fun validateCompilation(outputDir: File) {
    assert(compileKotlinFiles(outputDir))
}
