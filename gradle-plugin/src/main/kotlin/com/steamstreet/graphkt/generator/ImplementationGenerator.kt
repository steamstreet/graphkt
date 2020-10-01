package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

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

    private fun CodeBlock.Builder.fieldInitializationCode(fieldName: String, fieldType: Type<Type<*>>, inputs: List<InputValueDefinition>) {
        val kotlinFieldType = getKotlinType(fieldType)
        val baseFieldType = if (fieldType is NonNullType) fieldType.type else fieldType

        val params = inputs.map { it.name }.joinToString(",").let {
            if (it.isNotBlank()) {
                "($it)"
            } else it
        }

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


                    val scalarSerializer = schema.scalars().keys.find {
                        properties["scalar.$it.class"] == kotlinFieldType.canonicalName
                    }?.let {
                        scalarSerializer(it)
                    }

                    if (isScalar(baseFieldType)) {
                        if (isCustomScalar(baseFieldType)) {
                            if (kotlinFieldType.isNullable) {
                                add("%L$params?.let { json.encodeToJsonElement(%T, it) } ?: %T", fieldName, scalarSerializer, jsonNullType)
                            } else {
                                add("%L$params.let { json.encodeToJsonElement(%T, it) } ?: throw %T()", fieldName, scalarSerializer, NullPointerExceptionClass)
                            }
                        } else {
                            add("%T(%L)", ClassName("kotlinx.serialization.json", "JsonPrimitive"), buildCodeBlock {
                                buildFieldFetcher(fieldName, inputs)
                            })
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
        } else if (baseFieldType is ListType) {
            val baseType = baseFieldType.type
//            val baseKotlinType = getKotlinType(baseType)

            if (fieldType is NonNullType) {
                add("%T(%L$params.map·{ %L })", jsonArrayType, fieldName, buildCodeBlock {
                    fieldInitializationCode("it", baseType, emptyList())
                })
            } else {
                add("%L$params?.let·{ list -> %T(list.map·{ %L }) } ?: %T", fieldName, jsonArrayType, buildCodeBlock {
                    fieldInitializationCode("it", baseType, emptyList())
                }, jsonNullType)
            }
        }
    }


    fun CodeBlock.Builder.variableToInputParameter(def: InputValueDefinition) {
        val fieldName = def.name
        val fieldType = def.type
        val baseFieldType = if (fieldType is NonNullType) fieldType.type else fieldType
        val inputKotlinType = getKotlinType(def.type)

        fun addPrimitive(str: String) {
            val getterName = "$str${if (inputKotlinType.isNullable) "OrNull" else ""}"
            file.addImport("kotlinx.serialization.json", "jsonPrimitive", getterName)
            add("""it.inputParameter("$fieldName").jsonPrimitive.$getterName""")
        }

        if (inputKotlinType is ClassName) {
            when (inputKotlinType.canonicalName) {
                "kotlin.String" -> addPrimitive("content")
                "kotlin.Int" -> addPrimitive("int")
                "kotlin.Boolean" -> addPrimitive("boolean")
                "kotlin.Float" -> addPrimitive("float")
                else -> {
                    file.addImport("kotlinx.serialization.builtins", "serializer")
                    add("""json.decodeFromJsonElement(${inputKotlinType.simpleName}.serializer(), field.inputParameter("$fieldName"))""")
                }
            }
        } else if (baseFieldType is ListType) {
            val baseType = baseFieldType.type
            val elementType = getKotlinType(baseType)
            file.addImport("kotlinx.serialization.builtins", "serializer")

            if (elementType is ClassName) {
                add("""json.decodeFromJsonElement(%T(%L), field.inputParameter("$fieldName"))""",
                        ClassName("kotlinx.serialization.builtins", "ListSerializer"),
                        "${elementType.simpleName}.serializer()"
                )
            }
        }
    }

    fun execute() {
        file.suppress("FunctionName")
        schema.types().values.forEach { type ->
            if (type is ObjectTypeDefinition || type is InterfaceTypeDefinition) {
                val objectType = ClassName(packageName, type.name)
                val fieldDefinitions = if (type is ObjectTypeDefinition)
                    type.fieldDefinitions
                else if (type is InterfaceTypeDefinition) {
                    type.fieldDefinitions
                } else {
                    emptyList()
                }

                val requestSelectionClass = ClassName("com.steamstreet.graphkt.server", "RequestSelection")
                fieldDefinitions.forEach { field ->
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
                                    fieldInitializationCode(field.name, field.type, field.inputValueDefinitions
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
                            fieldDefinitions.forEach { field ->
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