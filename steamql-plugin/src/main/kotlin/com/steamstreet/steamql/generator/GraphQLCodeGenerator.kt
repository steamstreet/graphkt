package com.steamstreet.steamql.generator

import graphql.schema.idl.SchemaParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The task to generate code.
 */
open class GraphQLCodeGenerator : DefaultTask() {
    @InputFile
    fun getSchema(): File {
        return File(project.graphQL().schema)
    }

    @OutputDirectory
    fun getGeneratedOutputDir(): File {
        return File(project.buildDir, "graphql/generated")
    }

    @OutputDirectory
    fun getServerGeneratedOutputDir(): File {
        return File(project.buildDir, "graphql/server/generated")
    }

    @TaskAction
    fun doAction() {
        val schemaFile = getSchema()
        if (!schemaFile.exists()) {
            throw GradleException("You must specify a path to the schema file")
        }

        val schemaText = schemaFile.readText()
        val schema = SchemaParser().parse(schemaText)

        val outputDir = getGeneratedOutputDir()
        outputDir.mkdirs()

        getServerGeneratedOutputDir().mkdirs()

        DataTypesGenerator(schema, project.graphQL().basePackage, outputDir).execute()
        QueryGenerator(schema, project.graphQL().basePackage, outputDir).execute()
        SerializationGenerator(schema, project.graphQL().basePackage, outputDir).execute()
        ServerInterfacesGenerator(schema, project.graphQL().basePackage, outputDir).execute()

        WiringGenerator(schema, project.graphQL().basePackage, getServerGeneratedOutputDir()).execute()

    }
}