package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.*
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*


class ResponseParserGenerator(
    schema: TypeDefinitionRegistry,
    packageName: String,
    properties: Properties,
    outputDir: File
) : GeneratorBase(schema, packageName, properties, outputDir) {
    val responseType = ClassName("com.steamstreet.graphkt.client", "GraphQLResponse")

    private val responsesFile = FileSpec.builder("$packageName.client", "responses")

    fun execute() {
        responsesFile.suppress(
            "ComplexRedundantLet", "SimpleRedundantLet", "unused", "UnnecessaryVariable",
            "NestedLambdaShadowedImplicitParameter", "PropertyName"
        )

        schema.types().values.forEach { typeDef ->
            when (typeDef) {
                is ObjectTypeDefinition -> {
                    buildInterfaceForType(typeDef)
                    buildObject(typeDef)
                }

                is InterfaceTypeDefinition -> {
                    buildInterface(typeDef)
                    buildObject(typeDef)
                }
            }
        }

        responsesFile.build().writeTo(outputDir)
    }

    private fun buildInterface(typeDef: InterfaceTypeDefinition) {
        val interfaceType = TypeSpec.interfaceBuilder(typeDef.name).apply {
            addProperty(PropertySpec.builder("__typename", String::class).build())

            typeDef.fieldDefinitions.forEach { field ->
                val fieldType = getKotlinType(field.type, overriddenPackage = clientPackage)
                addProperty(PropertySpec.builder(field.name, fieldType).apply {
                }.build())
            }
        }.build()
        responsesFile.addType(interfaceType)
    }

    private fun buildInterfaceForType(typeDef: ImplementingTypeDefinition<*>) {
        if (typeDef is InterfaceTypeDefinition) {
            return // no need to generate an interface
        }

        val interfaceType = TypeSpec.interfaceBuilder(typeDef.name).apply {
            (typeDef as? ObjectTypeDefinition)?.implements?.map {
                getKotlinType(it, overriddenPackage = clientPackage).copy(nullable = false)
            }?.forEach {
                addSuperinterface(it)
            }

            val overriddenFields = if (typeDef is ObjectTypeDefinition) schema.getOverriddenFields(typeDef) else emptyList()

            typeDef.fieldDefinitions.forEach { field ->
                val fieldType = getKotlinType(field.type, overriddenPackage = clientPackage)
                addProperty(PropertySpec.builder(field.name, fieldType).apply {
                    if (overriddenFields.map { it.name }.contains(field.name)) {
                        addModifiers(KModifier.OVERRIDE)
                    }
                }.build())
            }
        }.build()
        responsesFile.addType(interfaceType)
    }

    private fun buildObject(typeDef: ImplementingTypeDefinition<*>) {
        val isInterfaceImpl = typeDef is InterfaceTypeDefinition
        val clientType = TypeSpec.classBuilder(typeDef.name + "Response")

        val jsonObjectType = ClassName("kotlinx.serialization.json", "JsonObject")
        clientType.primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("_response", responseType)
                .addParameter("element", jsonObjectType)
                .build()
        )
            .addProperty(
                PropertySpec.builder("_response", responseType)
                    .initializer("_response")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("element", jsonObjectType)
                    .initializer("element")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )

        val addedInterfaces = mutableSetOf<com.squareup.kotlinpoet.TypeName>()
        (typeDef as? ObjectTypeDefinition)?.implements?.map {
            getKotlinType(it, overriddenPackage = clientPackage).copy(nullable = false)
        }?.forEach {
            clientType.addSuperinterface(it)
            addedInterfaces.add(it)
        }
        val defaultInterface = ClassName(clientPackage, typeDef.name)
        if (!addedInterfaces.contains(defaultInterface)) {
            clientType.addSuperinterface(defaultInterface)
        }

        if (isInterfaceImpl) {
            clientType.addSuperinterface(ClassName(clientPackage, typeDef.name))
        }

        val fields = when (typeDef) {
            is ObjectTypeDefinition -> typeDef.fieldDefinitions
            is InterfaceTypeDefinition -> typeDef.fieldDefinitions
            else -> null
        }

        val overriddenFields = if (typeDef is ObjectTypeDefinition) schema.getOverriddenFields(typeDef) else emptyList()

        clientType.addProperty(PropertySpec.builder("__typename", String::class).apply {
            if (isInterfaceImpl || overriddenFields.isNotEmpty()) {
                addModifiers(KModifier.OVERRIDE)
            }
            getter(FunSpec.getterBuilder().apply {
                addCode(CodeBlock.builder().apply {
                    addStatement("return element[%S]!!.%T.content", "__typename", jsonPrimitiveFunction)
                }.build())
            }.build())
        }.build())

        clientType.addFunction(FunSpec.builder("hasField").apply {
            addParameter("key", String::class)

            addCode(CodeBlock.builder().apply {
                addStatement("return element.containsKey(key)")
            }.build())

            returns(Boolean::class)
        }.build())

        fields?.forEach { field ->
            val fieldType = getKotlinType(field.type, overriddenPackage = clientPackage)

            // build the parser definition
            clientType.addProperty(PropertySpec.builder(field.name, fieldType).apply {
                addModifiers(KModifier.OVERRIDE)
            }.getter(FunSpec.getterBuilder().apply {
                addCode(CodeBlock.builder().apply {
                    addStatement("_response.throwIfError(%S)", field.name)
                    addStatement("val result = element[%S]?.takeIf { it !is %T }?.let {", field.name, jsonNullType)
                    indent()

                    val statementType = if (field.type is NonNullType) {
                        (field.type as NonNullType).type
                    } else {
                        field.type
                    }
                    jsonExtractCode(statementType, field.name)
                    unindent()
                    addStatement("}")
                }.build())

                if (field.type is NonNullType) {
                    addStatement(
                        "return result ?: throw %T(%S)",
                        ClassName("kotlin", "NullPointerException"),
                        "${typeDef.name}: ${field.name}"
                    )
                } else {
                    addStatement("return result")
                }
            }.build()).build())
        }

        responsesFile.addType(clientType.build())
    }

    fun CodeBlock.Builder.jsonExtractCode(type: Type<Type<*>>, fieldName: String) {
        val isNonNull = type is NonNullType
        val baseType = (type as? NonNullType)?.type ?: type
        if (baseType is ListType) {
            beginControlFlow("it.%T.map", jsonArrayFunction)
            jsonExtractCode(baseType.type, fieldName)
            endControlFlow()
        } else if (baseType is TypeName) {
            val typeName = baseType.name
            val orNullText = if (!isNonNull) "OrNull" else ""

            when (typeName) {
                "String" -> if (!isNonNull) {
                    addStatement(
                        "it.%T.%T", jsonPrimitiveFunction,
                        ClassName("kotlinx.serialization.json", "content${orNullText}")
                    )
                } else {
                    addStatement("it.%T.content", jsonPrimitiveFunction)
                }

                "Int" -> addStatement(
                    "it.%T.%T", jsonPrimitiveFunction,
                    ClassName("kotlinx.serialization.json", "int${orNullText}")
                )

                "Float" -> addStatement(
                    "it.%T.%T", jsonPrimitiveFunction,
                    ClassName("kotlinx.serialization.json", "float${orNullText}")
                )

                "Boolean" -> addStatement(
                    "it.%T.%T", jsonPrimitiveFunction,
                    ClassName("kotlinx.serialization.json", "boolean${orNullText}")
                )

                else -> {
                    val isScalar = schema.scalars().containsKey(typeName)
                    val isCustomScalar = schema.customScalars().find {
                        it.name == typeName
                    } != null

                    if (isCustomScalar && scalarSerializer(typeName) != null) {
                        addStatement("%T.decodeFromJsonElement(%T, it)", jsonParserType, scalarSerializer(typeName))
                    } else if (isScalar) {
                        if (!isNonNull) {
                            addStatement(
                                "it.%T.%T", jsonPrimitiveFunction,
                                ClassName("kotlinx.serialization.json", "content${orNullText}")
                            )
                        } else {
                            addStatement("it.%T.content", jsonPrimitiveFunction)
                        }
                    } else {
                        val typeDefinition = schema.getType(baseType)
                        if (typeDefinition.isPresent) {
                            if (typeDefinition.get() is EnumTypeDefinition) {
                                addStatement(
                                    "%T.valueOf(it.%T.content)",
                                    getKotlinType(baseType).copy(nullable = false),
                                    jsonPrimitiveFunction
                                )
                            } else {
                                beginControlFlow("it.%T.let", jsonObjectFunction)
                                addStatement("${typeName}Response(_response.forElement(%S), it)", fieldName)
                                endControlFlow()
                            }
                        }
                    }
                }
            }
        }
    }
}