package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.EnumTypeDefinition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import graphql.language.InputObjectTypeDefinition
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

val builtIn = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS.map {
    it.name
}

/**
 * Generate the types and enums used by queries and server interfaces
 */
class DataTypesGenerator(
    schema: TypeDefinitionRegistry,
    packageName: String,
    properties: Properties,
    outputDir: File
) : GeneratorBase(schema, packageName, properties, outputDir) {

    val commonFile = FileSpec.builder(packageName, "common")

    fun execute() {
        commonFile.suppress("JSON_FORMAT_REDUNDANT_DEFAULT")
        commonFile.addImport("kotlinx.serialization.builtins", "serializer")
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
                    addStatement("ignoreUnknownKeys = true")
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
            val sealedClassName = ClassName(packageName, enumType.name)
            commonFile.addType(TypeSpec.classBuilder(sealedClassName).apply {
                addModifiers(KModifier.SEALED)
                val serializerClassName = ClassName(packageName, "${enumType.name}Serializer")
                addAnnotation(AnnotationSpec.builder(ClassName("kotlinx.serialization", "Serializable"))
                    .addMember("with = %T::class", serializerClassName)
                    .build())

                enumType.comments?.forEach {
                    this.addKdoc(it.content)
                }

                val objects = enumType.enumValueDefinitions.map { it.name } + "UNKNOWN"

                // Add companion object with serializer
                addType(TypeSpec.companionObjectBuilder().apply {
                    val serializerName = "${enumType.name}Serializer"
                    val serializerType = KSerializer::class.asClassName().parameterizedBy(sealedClassName)
                    addType(TypeSpec.classBuilder(serializerName).apply {
                        addSuperinterface(serializerType)

                        addProperty(PropertySpec.builder("descriptor", SerialDescriptor::class)
                            .addModifiers(KModifier.OVERRIDE)
                            .initializer("%T(%S, %T.STRING)", PrimitiveSerialDescriptor::class, enumType.name, PrimitiveKind::class)
                            .build())

                        addFunction(FunSpec.builder("serialize").apply {
                            addModifiers(KModifier.OVERRIDE)
                            addParameter("encoder", Encoder::class)
                            addParameter("value", sealedClassName)
                            addCode(CodeBlock.builder().apply {
                                addStatement("encoder.encodeString(value.toString())")
                            }.build())
                        }.build())

                        addFunction(FunSpec.builder("deserialize").apply {
                            addModifiers(KModifier.OVERRIDE)
                            returns(sealedClassName)
                            addParameter("decoder", Decoder::class)
                            addCode(CodeBlock.builder().apply {
                                addStatement("val stringValue = decoder.decodeString()")
                                beginControlFlow("return when (stringValue)")
                                objects.forEach { objectName ->
                                    if (objectName == "UNKNOWN") {
                                        addStatement("%S -> %N", objectName, objectName)
                                    } else {
                                        // Find original enum value definition for potential KDoc or other metadata if needed in future
                                        val originalEnumValue = enumType.enumValueDefinitions.find { it.name == objectName }
                                        // For now, just map string to object name
                                        addStatement("%S -> %N", originalEnumValue?.name ?: objectName, objectName)
                                    }
                                }
                                addStatement("else -> UNKNOWN")
                                endControlFlow()
                            }.build())
                        }.build())
                    }.build())
                }.build())

                enumType.enumValueDefinitions.forEach { enumValue ->
                    addType(TypeSpec.objectBuilder(enumValue.name).apply {
                        superclass(sealedClassName)
                        enumValue.comments?.forEach {
                            this.addKdoc(it.content)
                        }
                        // Override toString to return the name, for serialization
                        addFunction(FunSpec.builder("toString")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(String::class)
                            .addStatement("return %S", enumValue.name)
                            .build())
                    }.build())
                }

                // Add UNKNOWN case
                addType(TypeSpec.objectBuilder("UNKNOWN").apply {
                    superclass(sealedClassName)
                    addKdoc("Represents an unrecognized enum value.")
                    // Override toString for UNKNOWN
                    addFunction(FunSpec.builder("toString")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(String::class)
                        .addStatement("return %S", "UNKNOWN")
                        .build())
                }.build())

            }.build())
        }
    }
}