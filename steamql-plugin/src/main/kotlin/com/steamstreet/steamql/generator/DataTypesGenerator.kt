package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.*
import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

/**
 * Generate the types and enums used by queries and server interfaces
 */
class DataTypesGenerator(val schema: TypeDefinitionRegistry,
                         val packageName: String,
                         val outputDir: File) {
    val file = FileSpec.builder(packageName, "graphql-common")

    fun execute() {
        generateInputTypes()

        file.build().writeTo(outputDir)
    }

    fun generateInputTypes() {
        schema.types().values.mapNotNull { it as? InputObjectTypeDefinition }.forEach { inputType ->
            file.addType(TypeSpec.classBuilder(inputType.name).apply {
                addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

                inputType.comments?.forEach {
                    this.addKdoc(it.content)
                }
                primaryConstructor(FunSpec.constructorBuilder().apply {
                    inputType.inputValueDefinitions.forEach { inputValue ->
                        val typeName = getTypeName(schema, inputValue.type,
                                packageName = packageName)
                        addParameter(
                                ParameterSpec.builder(inputValue.name,
                                        typeName).apply {
                                    if (typeName.isNullable) {
                                        defaultValue("null")
                                    }
                                }.build()
                        )
                    }
                }.build())

                inputType.inputValueDefinitions.forEach { inputValue ->
                    addProperty(PropertySpec.builder(inputValue.name,
                            getTypeName(schema, inputValue.type, packageName = packageName))
                            .initializer(inputValue.name).build())
                }
            }.build())
        }

        schema.types().values.mapNotNull { it as? EnumTypeDefinition }.forEach { enumType ->
            file.addType(TypeSpec.enumBuilder(ClassName(packageName, enumType.name)).apply {
                enumType.comments?.forEach {
                    this.addKdoc(it.content)
                }
                enumType.enumValueDefinitions.forEach {
                    this.addEnumConstant(it.name, TypeSpec.anonymousClassBuilder().apply {
                        it.comments?.forEach {
                            this.addKdoc(it.content)
                        }
                    }.build())
                }
            }.build())
        }
    }
}