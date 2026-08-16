package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.steamstreet.graphkt.generator.schema.EnumType
import com.steamstreet.graphkt.generator.schema.InputObjectType
import com.steamstreet.graphkt.generator.schema.KotlinTypeMapper
import com.steamstreet.graphkt.generator.schema.ScalarType
import com.steamstreet.graphkt.generator.schema.SchemaModel
import com.steamstreet.graphkt.generator.schema.namedType
import com.steamstreet.graphkt.generator.schema.specificationScalarNames
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File
import java.util.Properties

/**
 * Generate the types and enums used by queries and server interfaces
 */
internal class DataTypesGenerator(
    private val schema: SchemaModel,
    private val packageName: String,
    private val properties: Properties,
    private val outputDir: File,
) {

    private val commonFile = FileSpec.builder(packageName, "common")
    private val typeMapper = KotlinTypeMapper(schema, packageName)
    private val customScalars = schema.types.filterIsInstance<ScalarType>()
        .filterNot { it.name in specificationScalarNames }

    fun execute() {
        commonFile.suppress("JSON_FORMAT_REDUNDANT_DEFAULT", "RedundantVisibilityModifier")
        commonFile.addImport("kotlinx.serialization.builtins", "serializer")
        generateInputTypes()
        scalarAliases()
        serializerModule()
        commonFile.build().writeTo(outputDir)
    }

    private fun scalarAliases() {
        customScalars.forEach { scalar ->
            val scalarClass = scalarClass(scalar.name)

            if (scalarClass == String::class.asClassName()) {
                commonFile.addTypeAlias(TypeAliasSpec.builder(scalar.name, String::class.asClassName()).build())
            } else {
                commonFile.addTypeAlias(TypeAliasSpec.builder(scalar.name, scalarClass).build())
            }
        }
    }

    private fun serializerModule() {
        val serializers = customScalars.filter { scalarSerializer(it.name) != null }

        if (serializers.isNotEmpty()) {
            commonFile.addProperty(
                PropertySpec.builder(
                    "serializerModule",
                    ClassName("kotlinx.serialization.modules", "SerializersModule")
                )
                    .initializer(CodeBlock.builder().apply {
                        this.beginControlFlow(
                            "%T",
                            ClassName("kotlinx.serialization.modules", "SerializersModule")
                        )

                        serializers.forEach { scalar ->
                            val scalarClass = scalarClass(scalar.name)
                            val scalarSerializer = scalarSerializer(scalar.name)

                            if (scalarSerializer != null) {
                                addStatement(
                                    "contextual(%T::class, %T)",
                                    scalarClass, scalarSerializer
                                )
                            }
                        }

                        this.endControlFlow()
                    }.build())
                    .build()
            )

        }

        val jsonSerializerType = ClassName("kotlinx.serialization.json", "Json")
        commonFile.addProperty(
            PropertySpec.builder(
                "json",
                jsonSerializerType
            )
                .addModifiers(KModifier.INTERNAL)
                .initializer(CodeBlock.builder().apply {
                    beginControlFlow("%T", jsonSerializerType)
                    if (serializers.isNotEmpty()) {
                        addStatement("serializersModule = serializerModule")
                    }
                    addStatement("ignoreUnknownKeys = true")
                    endControlFlow()
                }.build())
                .build()
        )
    }

    private fun scalarClass(name: String): ClassName =
        properties["scalar.$name.class"]?.toString()?.let(ClassName::bestGuess)
            ?: String::class.asClassName()

    private fun scalarSerializer(name: String): ClassName? =
        properties["scalar.$name.serializer"]?.toString()?.let(ClassName::bestGuess)

    fun generateInputTypes() {
        schema.types.filterIsInstance<InputObjectType>().forEach { inputType ->
            val inputTypeClass = TypeSpec.classBuilder(inputType.name).apply {
                addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

                addModifiers(KModifier.DATA)
                inputType.description?.let {
                    addKdoc("%L", it)
                }
                primaryConstructor(FunSpec.constructorBuilder().apply {
                    inputType.fields.forEach { inputValue ->
                        val typeName = typeMapper.map(inputValue.type)

                        addParameter(
                            ParameterSpec.builder(
                                inputValue.name,
                                typeName
                            ).apply {
                                if (typeName.isNullable) {
                                    defaultValue("null")
                                }

                                val scalarName = inputValue.type.namedType().name
                                if (scalarSerializer(scalarName) != null) {
                                    addAnnotation(ClassName("kotlinx.serialization", "Contextual"))
                                }
                            }.build()
                        )
                    }
                }.build())

                inputType.fields.forEach { inputValue ->
                    addProperty(
                        PropertySpec.builder(
                            inputValue.name,
                            typeMapper.map(inputValue.type)
                        )
                            .initializer(inputValue.name).build()
                    )
                }
            }
            commonFile.addType(inputTypeClass.build())
        }

        schema.types.filterIsInstance<EnumType>().forEach { enumType ->
            val enumSealedClassName = ClassName(packageName, enumType.name)
            val serializerClassName = ClassName(
                enumSealedClassName.packageName, enumSealedClassName.simpleName + "Serializer"
            )

            val enumSerializer = TypeSpec.objectBuilder(
                serializerClassName
            )
            enumSerializer.addSuperinterface(
                KSerializer::class.asClassName()
                    .parameterizedBy(enumSealedClassName)
            )

            enumSerializer.addProperty(
                PropertySpec.builder(
                    "descriptor",
                    SerialDescriptor::class, KModifier.OVERRIDE
                )
                    .initializer(
                        "%M(%S, %T.STRING)",
                        MemberName("kotlinx.serialization.descriptors", "PrimitiveSerialDescriptor"),
                        enumSealedClassName.simpleName,
                        PrimitiveKind::class
                    )
                    .build()
            )

            enumSerializer.addFunction(
                FunSpec.builder("serialize")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("encoder", Encoder::class)
                    .addParameter("value", enumSealedClassName)
                    .addStatement("encoder.encodeString(value.name)")
                    .build()
            )

            enumSerializer.addFunction(
                FunSpec.builder("deserialize")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(enumSealedClassName)
                    .addParameter("decoder", Decoder::class)
                    .addCode(
                        CodeBlock.builder()
                            .addStatement("return %T.valueOf(decoder.decodeString())", enumSealedClassName)
                            .build()
                    )
                    .build()
            )

            commonFile.addType(enumSerializer.build())

            commonFile.addType(TypeSpec.classBuilder(enumSealedClassName).apply {
                primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter(ParameterSpec.builder("name", String::class).build())
                        .build()
                )
                addProperty(
                    PropertySpec.builder("name", String::class)
                        .initializer("name").build()
                )
                addModifiers(KModifier.SEALED)
                addAnnotation(
                    AnnotationSpec.builder(Serializable::class)
                        .addMember("with = %T::class", serializerClassName)
                        .build()
                )

                addFunction(
                    FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE)
                        .returns(String::class)
                        .addStatement("return name")
                        .build()
                )

                enumType.values.forEach { enumValue ->
                    addType(
                        TypeSpec.objectBuilder(enumValue.name)
                            .superclass(enumSealedClassName)
                            .addSuperclassConstructorParameter(
                                "%S", enumValue.name
                            )
                            .build()
                    )
                }

                addType(
                    TypeSpec.classBuilder("Unknown")
                        .superclass(enumSealedClassName)
                        .primaryConstructor(
                            FunSpec.constructorBuilder()
                                .addParameter(ParameterSpec.builder("name", String::class).build())
                                .build()
                        )
                        .addSuperclassConstructorParameter(
                            "name"
                        )
                        .build()
                )

                addType(
                    TypeSpec.companionObjectBuilder().addFunction(
                        FunSpec.builder("valueOf")
                            .addParameter("name", String::class)
                            .returns(enumSealedClassName)
                            .addCode(
                                CodeBlock.builder()
                                    .beginControlFlow("return when (name)")
                                    .apply {
                                        enumType.values.forEach { enumValue ->
                                            addStatement("%S -> %L", enumValue.name, enumValue.name)
                                        }
                                        addStatement("else -> Unknown(name)")
                                    }
                                    .endControlFlow()
                                    .build()
                            )
                            .build()
                    ).apply {
                        val enumValues = enumType.values.map {
                            CodeBlock.of("%L", it.name)
                        }
                        val listType = List::class.asClassName().parameterizedBy(enumSealedClassName)
                        addProperty(
                            PropertySpec.builder("entries", listType).getter(
                                FunSpec.getterBuilder().addStatement("return listOf(%L)", enumValues.joinToCode())
                                    .build()
                            ).build()
                        )
                    }.build()
                )
            }.build())
        }
    }
}
