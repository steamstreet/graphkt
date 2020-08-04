package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import graphql.language.EnumTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

open class GraphQLGenerator(
        val schema: TypeDefinitionRegistry,
        val packageName: String,
        val properties: Properties,
        val outputDir: File
) {
    fun scalarClass(name: String): ClassName {
        return properties["scalar.${name}.class"]?.toString()?.let {
            ClassName.bestGuess(it)
        } ?: String::class.asClassName()
    }

    fun isScalar(type: Type<Type<*>>): Boolean {
        return if (type is NonNullType) {
            isScalar(type.type)
        } else if (type is graphql.language.TypeName) {
            val name = (type as? graphql.language.TypeName)?.name
            schema.customScalars().find { it.name == name } != null
        } else {
            false
        }
    }


    fun getKotlinType(type: Type<Type<*>>, postfix: String = ""): TypeName {
        return when (type) {
            is ListType -> {
                val baseClass = getKotlinType(type.type, postfix)
                ClassName("kotlin.collections", "List").parameterizedBy(baseClass).copy(nullable = true)
            }
            is NonNullType -> {
                getKotlinType(type.type, postfix).copy(nullable = false)
            }
            else -> {
                val typeName = (type as? graphql.language.TypeName)
                var typePackage = packageName
                var simpleName = typeName?.name + postfix

                if (typeName != null && isScalar(type)) {
                    return properties["scalar.${typeName.name}.class"]?.toString()?.let {
                        ClassName.bestGuess(it)
                    } ?: String::class.asClassName()
                }

                when (typeName?.name) {
                    "String" -> {
                        typePackage = "kotlin"
                        simpleName = "String"
                    }
                    "Boolean" -> {
                        typePackage = "kotlin"
                        simpleName = "Boolean"
                    }
                    "Float" -> {
                        typePackage = "kotlin"
                        simpleName = "Float"
                    }
                    "Int" -> {
                        typePackage = "kotlin"
                        simpleName = "Int"
                    }
                    "ID" -> {
                        typePackage = "com.steamstreet.steamql"
                        simpleName = "ID"
                    }
                    else -> {
                        // enum types DO NOT get a postfix
                        val schemaType = schema.types().values.find { it.name == typeName?.name }
                        if (schemaType is EnumTypeDefinition) {
                            simpleName = typeName?.name ?: ""
                        } else {
                            schema.scalars().values.find { it.name == typeName?.name }?.let {
                                simpleName = typeName?.name ?: ""
                            }
                        }
                    }
                }
                ClassName(typePackage, simpleName).copy(true)
            }
        }
    }
}