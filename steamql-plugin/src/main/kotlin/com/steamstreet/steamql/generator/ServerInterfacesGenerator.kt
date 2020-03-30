package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.*
import graphql.language.InputObjectTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

class ServerInterfacesGenerator(val schema: TypeDefinitionRegistry,
                                val packageName: String,
                                val outputDir: File) {

    val file = FileSpec.builder(packageName, "graphql-interfaces")

    fun execute() {
        schema.types().values.forEach { typeDef ->
            when (typeDef) {
                is ObjectTypeDefinition -> {
                    file.addType(TypeSpec.interfaceBuilder(typeDef.name)
                            .apply {
                                typeDef.comments?.forEach {
                                    this.addKdoc(it.content)
                                }

                                typeDef.fieldDefinitions?.forEach { field ->
                                    if (!field.inputValueDefinitions.isNullOrEmpty()) {
                                        addFunction(FunSpec.builder(field.name)
                                                .apply {
                                                    field.comments?.forEach {
                                                        this.addKdoc(it.content)
                                                    }

                                                    addModifiers(KModifier.ABSTRACT)
                                                    returns(getTypeName(schema, field.type, packageName = packageName))
                                                    field.inputValueDefinitions.forEach {
                                                        addParameter(ParameterSpec.builder(it.name, getTypeName(schema, it.type, packageName = packageName)).build())
                                                    }
                                                }.build())
                                    } else {
                                        addProperty(PropertySpec
                                                .builder(field.name, getTypeName(schema, field.type, packageName = packageName)).apply {
                                                    field.comments?.forEach {
                                                        this.addKdoc(it.content)
                                                    }
                                                }.build())
                                    }
                                }
                            }
                            .build())
                }
                is InputObjectTypeDefinition -> {
//                    printer.println("data class ${it.name} (")
//                    printer.println(it.inputValueDefinitions.map { field ->
//                        "val ${field.name}: ${typeString(field.type)}"
//                    }.joinToString(", "))
//                    printer.println(")")
                }
            }
        }
        file.build().writeTo(outputDir)
    }
}