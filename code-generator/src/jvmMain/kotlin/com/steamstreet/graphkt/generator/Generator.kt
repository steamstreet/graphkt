package com.steamstreet.graphkt.generator

import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

class Generator(
    val schema: TypeDefinitionRegistry,
    val packageName: String,
    val properties: Properties,
    val outputDir: File
) {
    fun generate(client: Boolean = true, server: Boolean = true) {
        DataTypesGenerator(schema, packageName, properties, outputDir).execute()
        if (client) {
            QueryGenerator(schema, packageName, properties, outputDir).execute()
            ResponseParserGenerator(schema, packageName, properties, outputDir).execute()
        }
        if (server) {
            ServerInterfacesGenerator(schema, packageName, properties, outputDir).execute()
            ServerMappingGenerator(schema, packageName, properties, outputDir).execute()
        }
    }
}