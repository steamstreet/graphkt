package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

val builtIn = ScalarInfo.STANDARD_SCALAR_DEFINITIONS.keys

/**
 * Generate the types and enums used by queries and server interfaces
 */
class DataTypesGenerator(schema: TypeDefinitionRegistry,
                         packageName: String,
                         properties: Properties,
                         outputDir: File) : GraphQLGenerator(schema, packageName, properties, outputDir) {

    val commonFile = FileSpec.builder(packageName, "common")

    fun execute() {
        generateInputTypes()
        scalarAliases()
        serializerModule()
        commonFile.build().writeTo(outputDir)
    }

    private fun scalarAliases() {
        schema.customScalars().forEach { scalar ->
            val scalarClass = scalarClass(scalar.name)

            if (scalarClass == String::class.asClassName()) {
                commonFile.addTypeAlias(TypeAliasSpec.builder(scalar.name, String::class.asClassName()).build())
            } else {
                commonFile.addTypeAlias(TypeAliasSpec.builder(scalar.name, scalarClass).build())
            }
        }
    }

    private fun serializerModule() {
        val serializers = schema.customScalars().filter { scalarSerializer(it.name) != null }

        if (serializers.isNotEmpty()) {
            commonFile.addProperty(PropertySpec.builder("serializerModule",
                    ClassName("kotlinx.serialization.modules", "SerializersModule"))
                    .initializer(CodeBlock.builder().apply {
                        this.beginControlFlow("%T",
                                ClassName("kotlinx.serialization.modules", "SerializersModule"))

                        serializers.forEach { scalar ->
                            val scalarClass = scalarClass(scalar.name)
                            val scalarSerializer = scalarSerializer(scalar.name)

                            if (scalarSerializer != null) {
                                addStatement("contextual(%T::class, %T)",
                                        scalarClass, scalarSerializer)
                            }
                        }

                        this.endControlFlow()
                    }.build())
                    .build())

        }

        val jsonSerializerType = ClassName("kotlinx.serialization.json", "Json")
        commonFile.addProperty(PropertySpec.builder("json",
                jsonSerializerType)
                .addModifiers(KModifier.INTERNAL)
                .initializer(CodeBlock.builder().apply {
                    beginControlFlow("%T", jsonSerializerType)
                    if (serializers.isNotEmpty()) {
                        addStatement("serializersModule = serializerModule")
                    }
                    endControlFlow()
                }.build())
                .build())
    }

    fun generateInputTypes() {
        schema.types().values.mapNotNull { it as? InputObjectTypeDefinition }.forEach { inputType ->
            val inputTypeClass = TypeSpec.classBuilder(inputType.name).apply {
                addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

                addModifiers(KModifier.DATA)
                inputType.comments?.forEach {
                    this.addKdoc(it.content)
                }
                primaryConstructor(FunSpec.constructorBuilder().apply {
                    inputType.inputValueDefinitions.forEach { inputValue ->
                        val typeName = getKotlinType(inputValue.type)

                        addParameter(
                                ParameterSpec.builder(inputValue.name,
                                        typeName).apply {
                                    if (typeName.isNullable) {
                                        defaultValue("null")
                                    }

                                    schema.findScalar(inputValue.type)?.let {
                                        properties["scalar.${it.name}.serializer"]
                                    }?.let {
                                        addAnnotation(ClassName("kotlinx.serialization", "Contextual"))
                                    }
//                                    schema.buildSerializableAnnotation(inputValue.type)?.let {
//                                        addAnnotation(it)
//                                    }
                                }.build()
                        )
                    }
                }.build())

                inputType.inputValueDefinitions.forEach { inputValue ->
                    addProperty(PropertySpec.builder(inputValue.name,
                            getKotlinType(inputValue.type))
                            .initializer(inputValue.name).build())
                }
            }
            commonFile.addType(inputTypeClass.build())
        }

        schema.types().values.mapNotNull { it as? EnumTypeDefinition }.forEach { enumType ->
            commonFile.addType(TypeSpec.enumBuilder(ClassName(packageName, enumType.name)).apply {
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