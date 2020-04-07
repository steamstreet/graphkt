package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File


class WiringGenerator(val schema: TypeDefinitionRegistry,
                      val packageName: String,
                      val outputDir: File) {

    val file = FileSpec.builder(packageName, "graphql-wiring")


    fun execute() {
        file.addImport("com.fasterxml.jackson.module.kotlin", "jacksonObjectMapper")
        file.addImport("com.steamstreet.steamql.server", "graphQLJackson", "convert")

        file.addProperty(PropertySpec.builder("jackson",
                ClassName("com.fasterxml.jackson.databind", "ObjectMapper")).apply {
            initializer("jacksonObjectMapper()")
        }.build())

        file.addFunction(FunSpec.builder("initWiring")
                .receiver(ClassName("graphql.schema.idl", "RuntimeWiring").nestedClass("Builder"))
                .apply {
                    schema.types().values.mapNotNull { it as? ObjectTypeDefinition }.forEach { typeDef ->
                        beginControlFlow("type(%S)", typeDef.name)
                        typeDef.fieldDefinitions?.filter {
                            !it.inputValueDefinitions.isNullOrEmpty()
                        }?.forEach {
                            beginControlFlow("it.dataFetcher(%S)", it.name)


                            val parameters = it.inputValueDefinitions?.joinToString(",") {
                                val inputType = schema.findType(it.type)
                                val typeName = getTypeName(schema, it.type, packageName = packageName)
                                if (inputType is InputObjectTypeDefinition) {
                                    """graphQLJackson.convert(it.getArgument<Map<String,Any>>("${it.name}"))"""
                                } else if (inputType is EnumTypeDefinition) {
                                    """it.getArgument<String>("${it.name}").let { ${typeName}.valueOf(it!!) }"""
                                } else {
                                    """it.getArgument<${typeName}>("${it.name}")"""
                                }
                            }

                            addStatement("it.getSource<${typeDef.name}>().${it.name}($parameters)")
                            endControlFlow()
                        }
                        addStatement("it")
                        endControlFlow()
                    }
                }
                .build())

        file.build().writeTo(outputDir)
    }
}