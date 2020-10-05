package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.TypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*


class ServerInterfacesGenerator(schema: TypeDefinitionRegistry,
                                packageName: String,
                                properties: Properties,
                                outputDir: File) : GraphQLGenerator(schema, packageName, properties, outputDir) {

    private val servicesFile = FileSpec.builder("$packageName.server", "services")

    fun execute() {
        servicesFile.suppress("PropertyName")

        schema.types().values.forEach { typeDef ->
            when (typeDef) {
                is ObjectTypeDefinition -> buildInterface(typeDef)
                is InterfaceTypeDefinition -> buildInterface(typeDef)
            }
        }

        servicesFile.build().writeTo(outputDir)
    }

    private fun buildInterface(typeDef: TypeDefinition<TypeDefinition<*>>) {
        val serverType = TypeSpec.interfaceBuilder(typeDef.name)
        (typeDef as? ObjectTypeDefinition)?.implements?.map {
            getKotlinType(it, overriddenPackage = serverPackage).copy(nullable = false)
        }?.forEach {
            serverType.addSuperinterface(it)
        }

        typeDef.comments?.forEach {
            serverType.addKdoc(it.content)
        }

        val fields = when (typeDef) {
            is ObjectTypeDefinition -> typeDef.fieldDefinitions
            is InterfaceTypeDefinition -> typeDef.fieldDefinitions
            else -> null
        }

        val overriddenFields = if (typeDef is ObjectTypeDefinition) schema.getOverriddenFields(typeDef) else emptyList()

        fields?.forEach { field ->
            val fieldType = getKotlinType(field.type, overriddenPackage = serverPackage)
            serverType.addFunction(FunSpec.builder(field.name)
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
        }

        servicesFile.addType(serverType.build())
    }
}