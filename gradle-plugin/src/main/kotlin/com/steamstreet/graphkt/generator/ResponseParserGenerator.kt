package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.*
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*


class ResponseParserGenerator(schema: TypeDefinitionRegistry,
                              packageName: String,
                              properties: Properties,
                              outputDir: File) : GraphQLGenerator(schema, packageName, properties, outputDir) {

    private val responsesFile = FileSpec.builder("$packageName.client", "responses")

    fun execute() {
        responsesFile.suppress("ComplexRedundantLet", "SimpleRedundantLet", "unused", "UnnecessaryVariable",
                "NestedLambdaShadowedImplicitParameter")

        schema.types().values.forEach { typeDef ->
            when (typeDef) {
                is ObjectTypeDefinition -> buildObject(typeDef)
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
            typeDef.fieldDefinitions.forEach { field ->
                val fieldType = getKotlinType(field.type, overriddenPackage = clientPackage)
                addProperty(PropertySpec.builder(field.name, fieldType).apply {
                }.build())
            }
        }.build()
        responsesFile.addType(interfaceType)
    }

    private fun buildObject(typeDef: TypeDefinition<TypeDefinition<*>>) {
        val isInterfaceImpl = typeDef is InterfaceTypeDefinition
        val clientType = TypeSpec.classBuilder(typeDef.name + (if (isInterfaceImpl) "Impl" else ""))

        val jsonObjectType = ClassName("kotlinx.serialization.json", "JsonObject")
        clientType.primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("element", jsonObjectType)
                .build())
                .addProperty(PropertySpec.builder("element", jsonObjectType)
                        .initializer("element")
                        .addModifiers(KModifier.PRIVATE)
                        .build())

        (typeDef as? ObjectTypeDefinition)?.implements?.map {
            getKotlinType(it, overriddenPackage = clientPackage).copy(nullable = false)
        }?.forEach {
            clientType.addSuperinterface(it)
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

        fields?.forEach { field ->
            val fieldType = getKotlinType(field.type, overriddenPackage = clientPackage)

            // build the parser definition
            clientType.addProperty(PropertySpec.builder(field.name, fieldType).apply {
                if (isInterfaceImpl || overriddenFields.map { it.name }.contains(field.name)) {
                    addModifiers(KModifier.OVERRIDE)
                }
            }.getter(FunSpec.getterBuilder().apply {
                addCode(CodeBlock.builder().apply {
                    addStatement("val result = element[%S]?.takeIf { it !is %T }?.let {", field.name, jsonNullType)
                    indent()

                    val statementType = if (field.type is NonNullType) {
                        (field.type as NonNullType).type
                    } else {
                        field.type
                    }
                    jsonExtractCode(statementType)
                    unindent()
                    addStatement("}")
                }.build())

                if (field.type is NonNullType) {
                    addStatement("return result ?: throw %T()", ClassName("kotlin", "NullPointerException"))
                } else {
                    addStatement("return result")
                }
            }.build()).build())
        }

        responsesFile.addType(clientType.build())
    }

    fun CodeBlock.Builder.jsonExtractCode(type: Type<Type<*>>) {
        val isNonNull = type is NonNullType
        val baseType = (type as? NonNullType)?.type ?: type
        if (baseType is ListType) {
            beginControlFlow("it.%T.map", jsonArrayFunction)
            jsonExtractCode(baseType.type)
            endControlFlow()
        } else if (baseType is TypeName) {
            val typeName = baseType.name
            val orNullText = if (!isNonNull) "OrNull" else ""

            when (typeName) {
                "String" -> if (!isNonNull) {
                    addStatement("it.%T.%T", jsonPrimitiveFunction,
                            ClassName("kotlinx.serialization.json", "content${orNullText}"))
                } else {
                    addStatement("it.%T.content", jsonPrimitiveFunction)
                }
                "Int" -> addStatement("it.%T.%T", jsonPrimitiveFunction,
                        ClassName("kotlinx.serialization.json", "int${orNullText}"))
                "Float" -> addStatement("it.%T.%T", jsonPrimitiveFunction,
                        ClassName("kotlinx.serialization.json", "float${orNullText}"))
                "Boolean" -> addStatement("it.%T.%T", jsonPrimitiveFunction,
                        ClassName("kotlinx.serialization.json", "boolean${orNullText}"))
                else -> {
                    val isScalar = schema.scalars().containsKey(typeName)
                    val isCustomScalar = schema.customScalars().find {
                        it.name == typeName
                    } != null

                    if (isScalar && !isCustomScalar) {
                        if (!isNonNull) {
                            addStatement("it.%T.%T", jsonPrimitiveFunction,
                                    ClassName("kotlinx.serialization.json", "content${orNullText}"))
                        } else {
                            addStatement("it.%T.content", jsonPrimitiveFunction)
                        }
                    } else {
                        beginControlFlow("it.%T.let", jsonObjectFunction)
                        if (isCustomScalar) {
                            addStatement("%T.decodeFromJsonElement(%T, it)", jsonParserType, scalarSerializer(typeName))
                        } else if (schema.isInterfaceOrUnion(baseType)) {
                            addStatement("${typeName}Impl(it)")
                        } else {
                            addStatement("${typeName}(it)")
                        }
                        endControlFlow()
                    }
                }
            }
        }
    }
}