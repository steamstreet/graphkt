package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.InputValueDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

val jsonArrayType = ClassName("kotlinx.serialization.json", "JsonArray")
val jsonElementType = ClassName("kotlinx.serialization.json", "JsonElement")
val jsonObjectType = ClassName("kotlinx.serialization.json", "JsonObject")
val jsonNullType = ClassName("kotlinx.serialization.json", "JsonNull")

/**
 * Generates the file that receives the requests and forwards along to the
 * implementation classes.
 */
class ImplementationGenerator(schema: TypeDefinitionRegistry,
                              packageName: String,
                              properties: Properties,
                              outputDir: File) : GraphQLGenerator(schema, packageName, properties, outputDir) {
    val file = FileSpec.builder(packageName, "graphql-service-mapping")

    fun CodeBlock.Builder.buildFieldFetcher(fieldName: String, inputs: List<InputValueDefinition>) {
        if (inputs.isEmpty()) {
            add("%L", fieldName)
        } else {
            add("%L(%L)", fieldName, inputs.map { it.name }.joinToString(", "))
        }
    }

    private fun CodeBlock.Builder.fieldInitializationCode(fieldName: String, kotlinFieldType: TypeName, inputs: List<InputValueDefinition>) {
        if (kotlinFieldType is ClassName) {
            when (kotlinFieldType.canonicalName) {
                "kotlin.String",
                "kotlin.Boolean",
                "kotlin.Int",
                "kotlin.Float" -> {
                    add("%T(%L)", ClassName("kotlinx.serialization.json", "JsonPrimitive"), buildCodeBlock {
                        buildFieldFetcher(fieldName, inputs)
                    })
                }

                else -> {
                    val params = inputs.map { it.name }.joinToString(",").let {
                        if (it.isNotBlank()) {
                            "($it)"
                        } else it
                    }

                    val scalarSerializer = schema.scalars().keys.find {
                        properties["scalar.$it.class"] == kotlinFieldType.canonicalName
                    }?.let {
                        scalarSerializer(it)
                    }

                    if (scalarSerializer != null) {
                        if (kotlinFieldType.isNullable) {
                            add("%L$params?.let { json.toJson(%T, it) } ?: %T", fieldName, scalarSerializer, jsonNullType)
                        } else {
                        }
                    } else {
                        if (kotlinFieldType.isNullable) {
                            add("%L$params?.gqlSelect(field) ?: %T", fieldName, jsonNullType)
                        } else {
                            add("%L$params.gqlSelect(field)", fieldName)
                        }
                    }
                }
            }
        } else if (kotlinFieldType is ParameterizedTypeName) {
            if (kotlinFieldType.rawType == ClassName("kotlin.collections", "List")) {
                if (kotlinFieldType.isNullable) {
                    add("%L?.let·{ list -> %T(list.map·{ %L }) } ?: %T", fieldName, jsonArrayType, buildCodeBlock {
                        fieldInitializationCode("it", kotlinFieldType.typeArguments.first(), emptyList())
                    }, jsonNullType)
                } else {
                    add("%T(%L.map·{ %L })", jsonArrayType, fieldName, buildCodeBlock {
                        fieldInitializationCode("it", kotlinFieldType.typeArguments.first(), emptyList())
                    })
                }
            }
        }
    }


    fun CodeBlock.Builder.variableToInputParameter(def: InputValueDefinition) {
        val fieldName = def.name
        val inputKotlinType = getKotlinType(def.type)

        fun addPrimitive(str: String) {
            add("""it.inputParameter("${fieldName}").primitive.$str${if (inputKotlinType.isNullable) "OrNull" else ""}""")
        }

        if (inputKotlinType is ClassName) {
            when (inputKotlinType.canonicalName) {
                "kotlin.String" -> addPrimitive("content")
                "kotlin.Int" -> addPrimitive("int")
                "kotlin.Boolean" -> addPrimitive("boolean")
                "kotlin.Float" -> addPrimitive("float")
                else -> {
                    add("""json.fromJson(${inputKotlinType.simpleName}.serializer(), field.inputParameter("${fieldName}"))""")
                }
            }
        } else if (inputKotlinType is ParameterizedTypeName) {
            if (inputKotlinType.rawType.canonicalName == "kotlin.collections.List") {
                val elementType = inputKotlinType.typeArguments.first()

                if (elementType is ClassName) {
                    file.addImport("kotlinx.serialization.builtins", "serializer")

                    add("""json.fromJson(%T(%L), field.inputParameter("$fieldName"))""",
                            ClassName("kotlinx.serialization.builtins", "ListSerializer"),
                            "${elementType.simpleName}.serializer()"
                    )
                }

            }
        }
    }

    fun execute() {
        file.suppress("FunctionName")
        schema.types().values.forEach { type ->
            if (type is ObjectTypeDefinition) {
                val objectType = ClassName(packageName, type.name)

                val requestSelectionClass = ClassName("com.steamstreet.graphkt.server", "RequestSelection")
                type.fieldDefinitions.forEach { field ->
                    val kotlinFieldType = getKotlinType(field.type)
                    val f = FunSpec.builder("gql_${field.name}")
                            .receiver(objectType)
                            .addModifiers(KModifier.SUSPEND)
                            .returns(jsonElementType)
                            .apply {
                                addParameter("field", requestSelectionClass)

                                field.inputValueDefinitions.forEach {
                                    addParameter(ParameterSpec.builder(it.name, getKotlinType(it.type)).build())
                                }

                                addCode("return %L", buildCodeBlock {
                                    fieldInitializationCode(field.name, kotlinFieldType, field.inputValueDefinitions
                                            ?: emptyList())
                                })
                            }
                            .build()

                    file.addFunction(f)
                }

                file.addFunction(FunSpec.builder("gqlSelect")
                        .receiver(objectType)
                        .addModifiers(KModifier.SUSPEND)
                        .addParameter("field", requestSelectionClass)
                        .apply {
                            beginControlFlow("val fields = field.children.associate {")
                            beginControlFlow("val value = when(it.name) {")
                            type.fieldDefinitions.forEach { field ->
                                if (field.inputValueDefinitions.isEmpty()) {
                                    addStatement("%S -> gql_%L(it)", field.name, field.name)
                                } else {
                                    addStatement("%S -> gql_%L(it, %L)", field.name, field.name,
                                            field.inputValueDefinitions.map {
                                                CodeBlock.builder().apply {
                                                    variableToInputParameter(it)
                                                }.build().toString()
                                            }.joinToString(", "))
                                }
                            }
                            addStatement("else -> throw %T()", ClassName("kotlin", "IllegalArgumentException"))
                            endControlFlow()
                            addStatement("it.name to value")
                            endControlFlow()
                            addStatement("return %T(fields)", jsonObjectType)
                        }
                        .returns(jsonElementType).build())
            }
        }
        file.build().writeTo(outputDir)
    }
}