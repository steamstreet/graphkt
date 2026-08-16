package com.steamstreet.graphkt.generator

import com.steamstreet.graphkt.generator.schema.SchemaModel
import com.steamstreet.graphkt.generator.schema.SchemaNormalizer
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
        generateSources(
            schemaModel = SchemaNormalizer().normalize(schema),
            packageName = packageName,
            properties = properties,
            features = GenerationFeatures(client = client, server = server),
            outputs = GenerationOutputs(outputDir),
        )
    }
}

internal fun generateSources(
    schemaModel: SchemaModel,
    packageName: String,
    properties: Properties,
    features: GenerationFeatures,
    outputs: GenerationOutputs,
) {
    outputs.common.mkdirs()
    DataTypesGenerator(schemaModel, packageName, properties, outputs.common).execute()

    if (features.client) {
        outputs.client.mkdirs()
        QueryGenerator(schemaModel, packageName, properties, outputs.client).execute()
        ResponseParserGenerator(schemaModel, packageName, properties, outputs.client).execute()
    }

    if (features.server) {
        outputs.server.mkdirs()
        ServerSchemaGenerator(schemaModel, packageName, outputs.server).execute()
        ServerInterfacesGenerator(schemaModel, packageName, outputs.server).execute()
        ServerMappingGenerator(schemaModel, packageName, properties, outputs.server).execute()
        ServerExecutionAdapterGenerator(schemaModel, packageName, outputs.server).execute()
    }
}
