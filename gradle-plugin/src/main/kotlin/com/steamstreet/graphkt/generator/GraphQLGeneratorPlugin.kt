package com.steamstreet.graphkt.generator

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin to generate code from a graphQL schema
 */
class GraphQLGeneratorPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create<GraphQLExtension>(EXTENSION_NAME,
                GraphQLExtension::class.java)
        val task = target.tasks.register(
            "generateGraphQLCode",
            GraphQLCodeGeneratorTask::class.java
        )

        // Every Kotlin compilation must run after code generation. This has to cover more than the
        // JVM-style "compileKotlin" task, because multiplatform projects also compile per-target
        // ("compileKotlinLinuxX64", "compileTestKotlinJvm") and compile shared source sets as
        // metadata ("compileCommonMainKotlinMetadata", "compileNativeMainKotlinMetadata").
        // configureEach is lazy, so tasks are not realized just to attach the dependency.
        target.tasks.configureEach {
            if (KOTLIN_COMPILE_TASK.matches(it.name)) {
                it.dependsOn(task)
            }
        }
    }

    private companion object {
        val KOTLIN_COMPILE_TASK = Regex("compile\\w*Kotlin\\w*")
    }
}