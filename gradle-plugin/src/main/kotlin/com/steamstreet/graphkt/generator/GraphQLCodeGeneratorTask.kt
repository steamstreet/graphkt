package com.steamstreet.graphkt.generator

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates GraphKt sources from all configured schema files. */
@CacheableTask
public abstract class GraphQLCodeGeneratorTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val schemaFiles: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val propertiesFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val derivedPropertiesFiles: ConfigurableFileCollection

    @get:Input
    public abstract val packageName: Property<String>

    @get:Input
    public abstract val generateClient: Property<Boolean>

    @get:Input
    public abstract val generateServer: Property<Boolean>

    @get:OutputDirectory
    public abstract val generatedOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    public abstract val serverGeneratedOutputDirectory: DirectoryProperty

    @TaskAction
    public fun generate() {
        val schemas = schemaFiles.files
            .flatMap { input ->
                if (input.isDirectory) {
                    input.walkTopDown().filter { file -> file.isFile && file.extension == "graphql" }.toList()
                } else {
                    listOf(input)
                }
            }
            .distinctBy { it.canonicalPath }
            .sortedBy { it.canonicalPath }
        if (schemas.isEmpty()) {
            throw GradleException("You must specify at least one GraphQL schema file")
        }
        val configuredProperties = propertiesFile.orNull?.asFile
        val derivedProperties = derivedPropertiesFiles.files.sortedBy { it.canonicalPath }
        if (configuredProperties == null && derivedProperties.size > 1) {
            throw GradleException("Multiple schema properties files exist. Configure graphKt.propertiesFile explicitly.")
        }

        GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = schemas,
                propertiesFile = configuredProperties ?: derivedProperties.singleOrNull(),
                packageName = packageName.get(),
                features = GenerationFeatures(
                    client = generateClient.get(),
                    server = generateServer.get(),
                ),
                outputs = GenerationOutputs(
                    common = generatedOutputDirectory.get().asFile,
                    client = generatedOutputDirectory.get().asFile,
                    server = serverGeneratedOutputDirectory.get().asFile,
                ),
            ),
        )
    }
}
