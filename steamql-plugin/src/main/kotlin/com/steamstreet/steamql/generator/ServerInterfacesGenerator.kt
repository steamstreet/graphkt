package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.*
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.TypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

class ServerInterfacesGenerator(val schema: TypeDefinitionRegistry,
                                val packageName: String,
                                val outputDir: File) {

    val file = FileSpec.builder(packageName, "graphql-interfaces")

    fun execute() {
        schema.types().values.forEach { typeDef ->
            when (typeDef) {
                is ObjectTypeDefinition -> buildInterface(typeDef)
                is InterfaceTypeDefinition -> buildInterface(typeDef)
            }
        }
        file.build().writeTo(outputDir)
    }

    private fun buildInterface(typeDef: TypeDefinition<TypeDefinition<*>>) {
        file.addType(TypeSpec.interfaceBuilder(typeDef.name).apply {
            (typeDef as? ObjectTypeDefinition)?.implements?.map {
                getTypeName(schema, it, "", packageName).copy(nullable = false)
            }?.forEach {
                addSuperinterface(it)
            }

            typeDef.comments?.forEach {
                this.addKdoc(it.content)
            }

            val fields = when (typeDef) {
                is ObjectTypeDefinition -> typeDef.fieldDefinitions
                is InterfaceTypeDefinition -> typeDef.fieldDefinitions
                else -> null
            }

            val overriddenFields = if (typeDef is ObjectTypeDefinition) schema.getOverriddenFields(typeDef) else emptyList()

            fields?.forEach { field ->
                if (!field.inputValueDefinitions.isNullOrEmpty()) {
                    addFunction(FunSpec.builder(field.name)
                            .apply {
                                field.comments?.forEach {
                                    this.addKdoc(it.content)
                                }

                                addModifiers(KModifier.ABSTRACT)
                                returns(getTypeName(schema, field.type, packageName = packageName))
                                field.inputValueDefinitions.forEach {
                                    addParameter(ParameterSpec.builder(it.name, getTypeName(schema, it.type, packageName = packageName)).build())
                                }
                            }.build())
                } else {
                    addProperty(PropertySpec
                            .builder(field.name, getTypeName(schema, field.type, packageName = packageName)).apply {
                                if (overriddenFields.find { it.name == field.name } != null) {
                                    modifiers.add(KModifier.OVERRIDE)
                                }

                                field.comments?.forEach {
                                    this.addKdoc(it.content)
                                }
                            }.build())
                }
            }
        }
                .build())
    }
}