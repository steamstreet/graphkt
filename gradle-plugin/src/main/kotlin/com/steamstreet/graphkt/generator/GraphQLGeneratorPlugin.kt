package com.steamstreet.graphkt.generator

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Configures GraphKt generation and Kotlin source-set integration. */
public class GraphQLGeneratorPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create(
            GRAPHKT_EXTENSION_NAME,
            GraphQLExtension::class.java,
        )
        target.extensions.add("GraphQL", extension)

        val generationTask = target.tasks.register(
            "generateGraphQLCode",
            GraphQLCodeGeneratorTask::class.java,
        ) { task ->
            task.group = "graphkt"
            task.description = "Generates Kotlin sources from GraphQL schema files."
            task.schemaFiles.from(extension.schemaFiles)
            task.propertiesFile.set(extension.propertiesFile)
            task.derivedPropertiesFiles.from(
                extension.schemaFiles.elements.map { schemaLocations ->
                    schemaLocations.flatMap { location ->
                        val input = location.asFile
                        if (input.isDirectory) {
                            input.walkTopDown()
                                .filter { file -> file.isFile && file.extension == "properties" }
                                .toList()
                        } else {
                            listOfNotNull(
                                input.resolveSibling("${input.nameWithoutExtension}.properties").takeIf { it.isFile },
                            )
                        }
                    }
                },
            )
            task.packageName.set(extension.packageName)
            task.generateClient.set(extension.client.enabled)
            task.generateServer.set(extension.server.enabled)
            task.generatedOutputDirectory.convention(target.layout.buildDirectory.dir("graphql/generated"))
            task.serverGeneratedOutputDirectory.convention(target.layout.buildDirectory.dir("graphql/server/generated"))
        }

        target.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            KotlinSourceSetWiring.wireMultiplatform(target, generationTask)
        }

        target.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            KotlinSourceSetWiring.wireJvm(target, generationTask)
        }
    }
}
