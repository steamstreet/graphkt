package com.steamstreet.steamql.generator

import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

class JsonParserGenerator(private val schema: TypeDefinitionRegistry,
                          private val packageName: String,
                          private val outputDir: File) {

}