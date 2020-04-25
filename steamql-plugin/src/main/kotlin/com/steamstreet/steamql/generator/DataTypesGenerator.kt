package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.StringValue
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

val builtIn = ScalarInfo.STANDARD_SCALAR_DEFINITIONS.keys

/**
 * Generate the types and enums used by queries and server interfaces
 */
class DataTypesGenerator(val schema: TypeDefinitionRegistry,
                         val packageName: String,
                         val outputDir: File) {
    val file = FileSpec.builder(packageName, "graphql-common")

    fun execute() {
        generateInputTypes()
        scalarTypes()
        file.build().writeTo(outputDir)
    }

    fun scalarTypes() {
        schema.customScalars().forEach { scalar ->
            var type = ClassName("kotlin", "String")
            scalar.directives.find { it.name == "SteamQLScalar" }?.let {
                (it.getArgument("class")?.value as? StringValue)?.value?.let {
                    type = ClassName(it.substringBeforeLast("."), it.substringAfterLast("."))
                }
            }

            file.addTypeAlias(TypeAliasSpec.builder(scalar.name, type).build())
        }
    }

    fun generateInputTypes() {
        schema.types().values.mapNotNull { it as? InputObjectTypeDefinition }.forEach { inputType ->
            val inputTypeClass = TypeSpec.classBuilder(inputType.name).apply {
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

                                    schema.buildSerializableAnnotation(inputValue.type)?.let {
                                        addAnnotation(it)
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

                val companion = TypeSpec.companionObjectBuilder().addFunction(
                        FunSpec.builder("fromArgument").apply {
                            this.returns(ClassName(packageName, inputType.name).copy(nullable = true))
                            val mapType = ClassName("kotlin.collections", "Map").parameterizedBy(
                                    ClassName("kotlin", "String"),
                                    ClassName("kotlin", "Any").copy(nullable = true)
                            ).copy(nullable = true)
                            this.addParameter(ParameterSpec.builder("arg", mapType).apply {
                            }.build())

                            val parameterTypes = inputType.inputValueDefinitions.map {
                                getTypeName(schema, it.type,
                                        packageName = packageName)
                            }
                            addStatement("if (arg == null) return null")

                            val parameterString = inputType.inputValueDefinitions.map {
                                """${it.name} = arg["${it.name}"] as %T"""
                            }.joinToString(", ")

                            addStatement("""return ${inputType.name}(${parameterString})""", *(parameterTypes.toTypedArray()))
                        }.build())

                addType(companion.build())
            }


            file.addType(inputTypeClass.build())
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