package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.*
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*


class InterfacesGenerator(schema: TypeDefinitionRegistry,
                          packageName: String,
                          properties: Properties,
                          outputDir: File) : GraphQLGenerator(schema, packageName, properties, outputDir) {

    val file = FileSpec.builder(packageName, "graphql-interfaces")
    private val parser = FileSpec.builder(packageName, "graphql-responses")

    fun execute() {
        parser.suppress("ComplexRedundantLet", "SimpleRedundantLet", "unused", "UnnecessaryVariable",
                "NestedLambdaShadowedImplicitParameter")
        file.suppress("PropertyName")

        schema.types().values.forEach { typeDef ->
            when (typeDef) {
                is ObjectTypeDefinition -> buildInterface(typeDef)
                is InterfaceTypeDefinition -> buildInterface(typeDef)
            }
        }

        file.build().writeTo(outputDir)
        parser.build().writeTo(outputDir)
    }

    private fun buildInterface(typeDef: TypeDefinition<TypeDefinition<*>>) {
        val interfaceType = TypeSpec.interfaceBuilder(typeDef.name)

        val parserType = TypeSpec.classBuilder(typeDef.name + "ResponseData")
                .addModifiers(KModifier.OPEN)
                .addSuperinterface(ClassName(packageName, typeDef.name))

        val jsonObjectType = ClassName("kotlinx.serialization.json", "JsonObject")
        parserType.primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("element", jsonObjectType)
                .build())
                .addProperty(PropertySpec.builder("element", jsonObjectType)
                        .initializer("element")
                        .addModifiers(KModifier.PRIVATE)
                        .build())

        (typeDef as? ObjectTypeDefinition)?.implements?.map {
            getKotlinType(it).copy(nullable = false)
        }?.forEach {
            interfaceType.addSuperinterface(it)
        }

        typeDef.comments?.forEach {
            interfaceType.addKdoc(it.content)
        }

        val fields = when (typeDef) {
            is ObjectTypeDefinition -> typeDef.fieldDefinitions
            is InterfaceTypeDefinition -> typeDef.fieldDefinitions
            else -> null
        }

        val overriddenFields = if (typeDef is ObjectTypeDefinition) schema.getOverriddenFields(typeDef) else emptyList()

        fields?.forEach { field ->
            val fieldType = getKotlinType(field.type)
            if (!field.inputValueDefinitions.isNullOrEmpty()) {
                interfaceType.addFunction(FunSpec.builder(field.name)
                        .apply {
                            field.comments?.forEach {
                                this.addKdoc(it.content)
                            }
                            addModifiers(KModifier.ABSTRACT, KModifier.SUSPEND)
                            returns(fieldType)
                            field.inputValueDefinitions.forEach {
                                addParameter(ParameterSpec.builder(it.name, getKotlinType(it.type)).build())
                            }
                        }.build())
            } else {
                interfaceType.addProperty(PropertySpec
                        .builder(field.name, fieldType).apply {
                            if (overriddenFields.find { it.name == field.name } != null) {
                                modifiers.add(KModifier.OVERRIDE)
                            }

                            field.comments?.forEach {
                                this.addKdoc(it.content)
                            }
                        }.build())
            }

            // build the parser definition

            parserType.addProperty(PropertySpec.builder(field.name, fieldType).apply {
                if (field.inputValueDefinitions.isNullOrEmpty()) {
                    addModifiers(KModifier.OVERRIDE)
                }
            }.getter(FunSpec.getterBuilder().apply {
                addCode(CodeBlock.builder().apply {
//                            addCode("return ")
                    addStatement("val result = element[%S]?.let {", field.name)
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

            // add an empty declaration for the function call
            if (!field.inputValueDefinitions.isNullOrEmpty()) {
                parserType.addFunction(FunSpec.builder(field.name)
                        .apply {
                            addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                            returns(fieldType)
                            field.inputValueDefinitions.forEach {
                                addParameter(ParameterSpec.builder(it.name, getKotlinType(it.type)).build())
                            }
                            addStatement("return ${field.name}")
                        }.build())
            }
        }

        file.addType(interfaceType.build())
        parser.addType(parserType.build())
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
                "String" -> addStatement("it.%T.%T", jsonPrimitiveFunction,
                        ClassName("kotlinx.serialization.json", "content${orNullText}"))
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

                    beginControlFlow("it.%T.let", jsonObjectFunction)
                    if (isCustomScalar) {
                        addStatement("json.decodeFromJsonElement(%T, it)", scalarSerializer(typeName))
                    } else if (isScalar) {
                        addStatement("it.%T.%T", jsonPrimitiveFunction,
                                ClassName("kotlinx.serialization.json", "content${orNullText}"))
                    } else {
                        addStatement("${typeName}ResponseData(it)")
                    }
                    endControlFlow()
                }
            }
        }
    }
}