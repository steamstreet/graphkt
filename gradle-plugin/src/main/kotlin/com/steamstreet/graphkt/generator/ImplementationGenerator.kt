package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.InputValueDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

val jsonArrayType = ClassName("kotlinx.serialization.json", "JsonArray")
val jsonElementType = ClassName("kotlinx.serialization.json", "JsonElement")
val jsonObjectType = ClassName("kotlinx.serialization.json", "JsonObject")
val jsonNullType = ClassName("kotlinx.serialization.json", "JsonNull")

/**
 * Generates the file that receives the requests and forwards along to the
 * implementation classes.
 */
class ImplementationGenerator(val schema: TypeDefinitionRegistry,
                              val packageName: String,
                              val outputDir: File) {
    val file = FileSpec.builder(packageName, "graphql-service-mapping")

    fun CodeBlock.Builder.buildFieldFetcher(fieldName: String, kotlinFieldType: TypeName, inputs: List<InputValueDefinition>) {
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
                        buildFieldFetcher(fieldName, kotlinFieldType, inputs)
                    })
                }

                else -> {
                    val params = inputs.map { it.name }.joinToString(",").let {
                        if (it.isNotBlank()) {
                            "($it)"
                        } else it
                    }
                    if (kotlinFieldType.isNullable) {
                        add("%L$params?.gqlSelect(field) ?: %T", fieldName, jsonNullType)
                    } else {
                        add("%L$params.gqlSelect(field)", fieldName)
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
                    add("%L(%L.map·{ %L })", jsonArrayType, fieldName, buildCodeBlock {
                        fieldInitializationCode("it", kotlinFieldType.typeArguments.first(), emptyList())
                    })
                }
            }
        }
    }


    fun CodeBlock.Builder.variableToInputParameter(def: InputValueDefinition) {
        val fieldName = def.name
        val inputKotlinType = getTypeName(schema, def.type, "", packageName)

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
                    add("""graphQLJson.fromJson(${inputKotlinType.simpleName}.serializer(), field.inputParameter("${fieldName}"))""")
                }
            }
        } else if (inputKotlinType is ParameterizedTypeName) {
            if (inputKotlinType.rawType.canonicalName == "kotlin.collections.List") {
                val elementType = inputKotlinType.typeArguments.first()

                if (elementType is ClassName) {
                    val serializer = when (elementType.canonicalName) {
                        "kotlin.String" -> "String.serializer()"
                        "kotlin.Int" -> "Int.serializer()"
                        "kotlin.Boolean" -> "Boolean.serializer()"
                        "kotlin.Float" -> "Float.serializer()"
                        else -> ""
                    }

                    file.addImport("kotlinx.serialization.builtins", "serializer")

                    add("""graphQLJson.fromJson(%T(%L), field.inputParameter("${fieldName}"))""",
                            ClassName("kotlinx.serialization.builtins", "ListSerializer"),
                            "${elementType.simpleName}.serializer()"
                    )
                }

            }
        }
    }

    fun execute() {
        file.suppress("FunctionName")

        val jsonClass = ClassName("kotlinx.serialization.json", "Json")
        file.addProperty(PropertySpec.builder("graphQLJson",
                jsonClass
        ).apply {
            addModifiers(KModifier.INTERNAL)
            initializer("%T(%T.Stable)", jsonClass, ClassName("kotlinx.serialization.json", "JsonConfiguration"))
        }.build())

        schema.types().values.forEach { type ->
            if (type is ObjectTypeDefinition) {
                val objectType = ClassName(packageName, type.name)

                val requestSelectionClass = ClassName("com.steamstreet.graphkt.server", "RequestSelection")
                type.fieldDefinitions.forEach { field ->
                    val kotlinFieldType = getTypeName(schema, field.type, packageName = packageName)
                    val f = FunSpec.builder("gql_${field.name}")
                            .receiver(objectType)
                            .returns(jsonElementType)
                            .apply {
                                addParameter("field", requestSelectionClass)

                                field.inputValueDefinitions.forEach {
                                    addParameter(ParameterSpec.builder(it.name, getTypeName(schema, it.type, packageName = packageName)).build())
                                }

//                                if (kotlinFieldType is ParameterizedTypeName) {
//                                    addComment("Insert comment to address bug with KotlinPoet")
//                                }
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