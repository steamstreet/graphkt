package com.steamstreet.graphkt.generator

import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.tasks.TaskProvider

/** Kotlin source-set integration without a runtime dependency on one Kotlin plugin version. */
internal object KotlinSourceSetWiring {
    fun wireMultiplatform(
        project: Project,
        generationTask: TaskProvider<GraphQLCodeGeneratorTask>,
    ) {
        wire(project, "commonMain", generationTask)
    }

    fun wireJvm(
        project: Project,
        generationTask: TaskProvider<GraphQLCodeGeneratorTask>,
    ) {
        wire(project, "main", generationTask)
    }

    private fun wire(
        project: Project,
        sourceSetName: String,
        generationTask: TaskProvider<GraphQLCodeGeneratorTask>,
    ) {
        project.kotlinSourceSets().named(sourceSetName) { sourceSet ->
            val kotlinSources = sourceSet.kotlinSources()
            kotlinSources.srcDir(generationTask.flatMap { it.generatedOutputDirectory })
            kotlinSources.srcDir(generationTask.flatMap { it.serverGeneratedOutputDirectory })
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Project.kotlinSourceSets(): NamedDomainObjectCollection<Any> {
    val kotlinExtension = extensions.getByName("kotlin")
    val sourceSets = kotlinExtension.javaClass.methods
        .firstOrNull { method -> method.name == "getSourceSets" && method.parameterCount == 0 }
        ?.invoke(kotlinExtension)
        ?: error("The applied Kotlin plugin does not expose source sets")
    return sourceSets as? NamedDomainObjectCollection<Any>
        ?: error("The applied Kotlin plugin returned an unsupported source-set container")
}

private fun Any.kotlinSources(): SourceDirectorySet {
    val sources = javaClass.methods
        .firstOrNull { method -> method.name == "getKotlin" && method.parameterCount == 0 }
        ?.invoke(this)
        ?: error("Kotlin source set does not expose Kotlin sources")
    return sources as? SourceDirectorySet
        ?: error("The applied Kotlin plugin returned an unsupported Kotlin source directory set")
}
