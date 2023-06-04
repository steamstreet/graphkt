package com.steamstreet.graphkt.generator

import graphql.schema.idl.SchemaParser
import java.io.File
import java.util.*

class GraphQLCodeGenerator(
    val schemaFiles: List<File>,
    val propertiesFile: File?,
    val outputDir: File,
    val serverOutputDir: File,
    val basePackage: String,
    val generateClient: Boolean,
    val generateServer: Boolean
) {
    fun execute() {
        val schemaFile = schemaFiles.first()
        if (!schemaFile.exists()) {
            throw IllegalArgumentException("Missing schema file")
        }

        val parser = SchemaParser()
        val schema = parser.parse(schemaFile)

        schemaFiles.forEach {
            val nested = parser.parse(it)
            schema.merge(nested)
        }

        outputDir.mkdirs()
        serverOutputDir.mkdirs()

        val properties = Properties().also { properties ->
            propertiesFile?.inputStream()?.use {
                properties.load(it)
            }
        }

        DataTypesGenerator(schema, basePackage, properties, outputDir).execute()
        if (generateClient) {
            QueryGenerator(schema, basePackage, properties, outputDir).execute()
            ResponseParserGenerator(schema, basePackage, properties, outputDir).execute()
        }
        if (generateServer) {
            ServerInterfacesGenerator(schema, basePackage, properties, outputDir).execute()
            ServerMappingGenerator(schema, basePackage, properties, outputDir).execute()
        }
    }
}