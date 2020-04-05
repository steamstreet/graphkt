package com.steamstreet.steamql.generator

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin to generate code from a graphQL schema
 */
class GraphQLGeneratorPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create<GraphQLExtension>(EXTENSION_NAME,
                GraphQLExtension::class.java)
        val task = target.tasks.register("generateGraphQLCode",
                GraphQLCodeGenerator::class.java)

        target.afterEvaluate { _ ->
            target.tasks.filter {
                it.name.startsWith("compileKotlin")
            }.forEach {
                it.dependsOn(task)
            }
        }
    }
}

