package com.steamstreet.graphkt.generator

import java.io.File

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
        GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = schemaFiles,
                propertiesFile = propertiesFile,
                packageName = basePackage,
                features = GenerationFeatures(client = generateClient, server = generateServer),
                outputs = GenerationOutputs(
                    common = outputDir,
                    client = outputDir,
                    server = serverOutputDir,
                ),
            ),
        )
    }
}
