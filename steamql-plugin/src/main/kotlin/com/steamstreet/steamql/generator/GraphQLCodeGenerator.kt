package com.steamstreet.steamql.generator

import graphql.schema.idl.SchemaParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.*

/**
 * The task to generate code.
 */
open class GraphQLCodeGenerator : DefaultTask() {
    @InputFile
    fun getSchema(): File {
        return File(project.graphQL().schema)
    }

    @InputFile
    fun getProperties(): File {
        return File("${getSchema().nameWithoutExtension}.properties")
    }

    @InputFiles
    fun getSchemaFiles(): List<File> {
        val schemaFile = getSchema()
        return schemaFile.parentFile.listFiles { dir, name ->
            name != schemaFile.name && name.endsWith(".graphql")
        }?.toList() ?: emptyList()
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

        val parser = SchemaParser()
        val schema = parser.parse(schemaFile)

        val schemaFiles = getSchemaFiles()
        schemaFiles.forEach {
            val nested = parser.parse(it)
            schema.merge(nested)
        }

        val outputDir = getGeneratedOutputDir()
        outputDir.mkdirs()

        getServerGeneratedOutputDir().mkdirs()

        val properties = Properties().also { properties ->
            getProperties().takeIf { it.exists() }?.inputStream()?.use {
                properties.load(it)
            }
        }

        DataTypesGenerator(schema, project.graphQL().basePackage, properties, outputDir).execute()
        QueryGenerator(schema, project.graphQL().basePackage, outputDir).execute()
//        SerializationGenerator(schema, project.graphQL().basePackage, outputDir).execute()
        InterfacesGenerator(schema, project.graphQL().basePackage, properties, outputDir).execute()

        ImplementationGenerator(schema, project.graphQL().basePackage, outputDir).execute()

//        WiringGenerator(schema, project.graphQL().basePackage, getServerGeneratedOutputDir()).execute()

    }
}