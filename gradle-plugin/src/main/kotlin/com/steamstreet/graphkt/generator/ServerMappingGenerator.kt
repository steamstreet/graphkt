package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.*
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

/**
 * Generates the file that receives the requests and forwards along to the
 * implementation classes.
 */
class ServerMappingGenerator(schema: TypeDefinitionRegistry,
                             packageName: String,
                             properties: Properties,
                             outputDir: File) : GraphQLGenerator(schema, packageName, properties, outputDir) {
    val file = FileSpec.builder(serverPackage, "service-mapping")

    fun CodeBlock.Builder.buildFieldFetcher(fieldName: String, inputs: List<InputValueDefinition>?) {
        if (inputs == null) {
            add("%L", fieldName)
        } else if (inputs.isEmpty()) {
            add("%L", fieldName)
        } else {
            add("%L(%L)", fieldName, inputs.map { it.name }.joinToString(", "))
        }
    }

    private fun CodeBlock.Builder.fieldInitializationCode(fieldName: String, fieldType: Type<Type<*>>, inputs: List<InputValueDefinition>?) {
        val kotlinFieldType = getKotlinType(fieldType)
        val baseFieldType = if (fieldType is NonNullType) fieldType.type else fieldType

        val params = inputs?.map { it.name }?.joinToString(",")?.let {
            if (it.isNotBlank()) {
                "($it)"
            } else ""
        } ?: ""

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
                    if (isEnum(baseFieldType)) {
                        add("%T(%L)", ClassName("kotlinx.serialization.json", "JsonPrimitive"), buildCodeBlock {
                            add("%L?.name", fieldName)
                        })
                    } else if (isScalar(baseFieldType)) {
                        if (isCustomScalar(baseFieldType)) {
                            val scalarSerializer = scalarSerializer((baseFieldType as TypeName).name)
                            if (kotlinFieldType.isNullable) {
                                add("%L$params?.let { %T.encodeToJsonElement(%T, it) } ?: %T", fieldName, jsonParserType, scalarSerializer, jsonNullType)
                            } else {
                                add("%L$params.let { %T.encodeToJsonElement(%T, it) } ?: throw %T()", fieldName, jsonParserType, scalarSerializer, NullPointerExceptionClass)
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

            if (fieldType is NonNullType) {
                add("%T(%L$params.map·{ %L })", jsonArrayType, fieldName, buildCodeBlock {
                    fieldInitializationCode("it", baseType, null)
                })
            } else {
                add("%L$params?.let·{ list -> %T(list.map·{ %L }) } ?: %T", fieldName, jsonArrayType, buildCodeBlock {
                    fieldInitializationCode("it", baseType, null)
                }, jsonNullType)
            }
        }
    }


    fun CodeBlock.Builder.variableToInputParameter(def: InputValueDefinition) {
        val fieldName = def.name
        val fieldType = def.type
        val baseFieldType = if (fieldType is NonNullType) fieldType.type else fieldType
        val inputKotlinType = getKotlinType(def.type)

        fun CodeBlock.Builder.addPrimitive(str: String) {
            val getterName = "$str${if (inputKotlinType.isNullable) "OrNull" else ""}"
            if (!(str == "content" && !inputKotlinType.isNullable)) {
                file.addImport("kotlinx.serialization.json", "jsonPrimitive", getterName)
            }
            add("""it.inputParameter("$fieldName").jsonPrimitive.$getterName""")
        }

        if (inputKotlinType is ClassName) {
            when (inputKotlinType.canonicalName) {
                "kotlin.String" -> this.addPrimitive("content")
                "kotlin.Int" -> this.addPrimitive("int")
                "kotlin.Boolean" -> this.addPrimitive("boolean")
                "kotlin.Float" -> this.addPrimitive("float")
                else -> {
                    file.addImport("kotlinx.serialization.builtins", "serializer")
                    file.addImport(jsonParserType.packageName, jsonParserType.simpleName)

                    if (isEnum(baseFieldType)) {
                        add("""%T.valueOf(%L)""", inputKotlinType, buildCodeBlock {
                            this.addPrimitive("content")
                        })
                    } else {
                        add("""json.decodeFromJsonElement(${inputKotlinType.simpleName}.serializer(), it.inputParameter("$fieldName"))""")
                    }
                }
            }
        } else if (baseFieldType is ListType) {
            val baseType = baseFieldType.type
            val elementType = getKotlinType(baseType)
            file.addImport("kotlinx.serialization.builtins", "serializer")
            file.addImport(jsonParserType.packageName, jsonParserType.simpleName)

            if (elementType is ClassName) {
                file.addImport("kotlinx.serialization.builtins", "ListSerializer")

                addStatement("""json.decodeFromJsonElement(ListSerializer(%L), it.inputParameter("$fieldName"))""",
                        "${elementType.simpleName}.serializer()"
                )
            }
        }
    }

    fun execute() {
        file.suppress("FunctionName", "UNUSED_PARAMETER")
        schema.types().values.forEach { type ->
            if (type is ObjectTypeDefinition || type is InterfaceTypeDefinition) {
                val objectType = ClassName(serverPackage, type.name)
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

                            if (type is InterfaceTypeDefinition) {
                                addStatement(""""__typename" -> %T(%L)""", jsonPrimitiveType, CodeBlock.builder().apply {
                                    this.beginControlFlow("when (this) {")
                                    schema.types().values.filter {
                                        it is ObjectTypeDefinition && it.implements.mapNotNull { (it as? TypeName)?.name }.contains(type.name)
                                    }.forEach {
                                        addStatement("is %L -> %S", it.name, it.name)
                                    }
                                    addStatement("else -> throw %T()", ClassName("kotlin", "IllegalArgumentException"))
                                    endControlFlow()
                                }.build().toString())
                            } else {
                                addStatement(""""__typename" -> %T("${type.name}")""", jsonPrimitiveType)
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