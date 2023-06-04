package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

/**
 * Generates classes used for serialization of GraphQL results.
 */
class SerializationGenerator(
    schema: TypeDefinitionRegistry,
    packageName: String,
    properties: Properties,
    outputDir: File
) : GeneratorBase(schema, packageName, properties, outputDir) {
    val file = FileSpec.builder(packageName, "graphql-serializable")

    fun execute() {
        file.suppress("unused")

        schema.types().values.forEach { typeDef ->
            when (typeDef) {
                is ObjectTypeDefinition -> {
                    buildObjectType(typeDef)
                }
                is InputObjectTypeDefinition -> {

                }
                is InterfaceTypeDefinition -> {
                    buildInterface(typeDef)
                }
            }
        }

        buildRootClass("Query")
        buildRootClass("Mutation")

        buildExecutionCallers()

        file.build().writeTo(outputDir)
    }


    fun buildRootClass(type: String) {
        file.addType(TypeSpec.classBuilder("${type}Response").apply {
            addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

            val errorClass = ClassName("com.steamstreet.graphkt.client", "GraphQLError")
            val errorList = ClassName("kotlin.collections", "List").parameterizedBy(errorClass)
                    .copy(nullable = true)

            primaryConstructor(FunSpec.constructorBuilder().apply {
                addParameter(ParameterSpec.builder("data", ClassName(packageName, "${type}Data").copy(nullable = true)).defaultValue("null").build())
                addParameter(ParameterSpec.builder("errors", errorList).defaultValue("null").build())
            }.build())
            addProperty(PropertySpec.builder("data", ClassName(packageName, "${type}Data").copy(nullable = true)).initializer("data").build())
            addProperty(PropertySpec.builder("errors", errorList).initializer("errors").build())
        }.build())
    }

    fun buildExecutionCallers() {
        file.addImport("kotlinx.serialization.json", "Json", "JsonConfiguration")
        file.addImport("com.steamstreet.graphkt.client", "GraphQLClientException")

        schema.schemaDefinition().get().operationTypeDefinitions.forEach {
            val operationName = it.typeName.name
            file.addFunction(FunSpec.builder(it.name)
                    .addModifiers(KModifier.SUSPEND)
                    .receiver(ClassName("com.steamstreet.graphkt.client", "GraphQLClient"))
                    .addParameter(ParameterSpec.builder("name", String::class.asTypeName().copy(nullable = true)).defaultValue("null").build())
                    .addParameter(ParameterSpec.builder("block", LambdaTypeName.get(ClassName(packageName, "${operationName}Query"), emptyList(),
                            ClassName("kotlin", "Unit"))).build())
                    .returns(TypeVariableName("${operationName}Data"))
                    .addStatement("val data = execute(name) { ${it.name} { block() } }")
                    .addStatement("val responseEnvelope = Json { ignoreUnknownKeys=true }.parse(${operationName}Response.serializer(), data)")
                    .beginControlFlow("if (!responseEnvelope.errors.isNullOrEmpty())")
                    .addStatement("throw GraphQLClientException(responseEnvelope.errors, responseEnvelope.data)")
                    .endControlFlow()
                    .addStatement("return responseEnvelope.data!!")
                    .build())
        }
    }


    fun buildInterface(typeDef: InterfaceTypeDefinition) {
        file.addType(TypeSpec.interfaceBuilder(typeDef.name + "Data").apply {
            typeDef.fieldDefinitions.forEach { field ->
                val typeName = getKotlinType(field.type, "Data")
                addProperty(PropertySpec.builder(field.name, typeName.copy(nullable = true)).also { property ->
                    schema.buildSerializableAnnotation(field.type)?.let {
                        property.addAnnotation(it)
                    }
                }.build())
            }
        }.build())
    }

    fun buildObjectType(typeDef: ObjectTypeDefinition) {
        val implementClasses = typeDef.implements.map {
            getKotlinType(it, "Data").copy(nullable = false)
        }

        file.addType(TypeSpec.classBuilder(typeDef.name + "Data").apply {
            if (typeDef.fieldDefinitions.size > 0) {
                addModifiers(KModifier.DATA)
            }
            addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

            implementClasses.forEach {
                addSuperinterface(it)
            }

            primaryConstructor(FunSpec.constructorBuilder().apply {
                typeDef.fieldDefinitions.forEach { field ->
                    val typeName = getKotlinType(field.type, "Data")
                    // data fields are ALWAYS nullable when parsing data results.
                    addParameter((ParameterSpec.builder(field.name,
                            typeName.copy(nullable = true)).defaultValue("null").build()))
                }
            }.build())

            val overriddenFields = schema.getOverriddenFields(typeDef)

            typeDef.fieldDefinitions.forEach { field ->
                val typeName = getKotlinType(field.type, "Data")
                addProperty(PropertySpec.builder(field.name, typeName.copy(nullable = true)).initializer(field.name).also { property ->
                    if (overriddenFields.find { it.name == field.name } != null) {
                        property.modifiers.add(KModifier.OVERRIDE)
                    }
                    schema.buildSerializableAnnotation(field.type)?.let {
                        property.addAnnotation(it)
                    }
                }.build())
            }
        }.build())
    }
}